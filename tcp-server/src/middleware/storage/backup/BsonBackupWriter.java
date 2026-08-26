package middleware.storage.backup;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.bson.BsonBinaryWriter;
import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

/**
 * Tek bir koleksiyon icin BSON parcali (chunked) yedek yazici.
 *
 * Dosya adi:
 *   {database}_{collection}.bson
 *   {database}_{collection}_1.bson
 *   {database}_{collection}_2.bson
 *
 * Yazim akisi: .tmp dosyasina yazilir → fsync → ATOMIC_MOVE ile .bson.
 * maxChunkBytes asilinca mevcut parca kapatilir ve sonraki acilir.
 */
public class BsonBackupWriter implements AutoCloseable {

    private static final DocumentCodec CODEC = new DocumentCodec();
    private static final EncoderContext ENCODER_CONTEXT = EncoderContext.builder().build();

    private final Path snapshotDir;
    private final String database;
    private final String collection;
    private final long maxChunkBytes;

    private int chunkIndex = 0;
    private long currentChunkBytes = 0;
    private long recordCount = 0;

    private FileOutputStream fileStream;
    private BufferedOutputStream out;
    private Path currentTmp;
    private Path currentTarget;

    private final List<String> filesCreated = new ArrayList<>();
    private boolean closed = false;

    public BsonBackupWriter(Path snapshotDir, String database, String collection, long maxChunkBytes) {
        if (snapshotDir == null) throw new IllegalArgumentException("snapshotDir is required");
        if (database == null || database.isBlank()) throw new IllegalArgumentException("database is required");
        if (collection == null || collection.isBlank()) throw new IllegalArgumentException("collection is required");
        if (maxChunkBytes < 1) throw new IllegalArgumentException("maxChunkBytes must be >= 1");

        this.snapshotDir = snapshotDir;
        this.database = database;
        this.collection = collection;
        this.maxChunkBytes = maxChunkBytes;
    }

    /** Kaydi BSON olarak yazar; limit asilirsa parcayi dondurur. */
    public void write(Map<String, Object> record) {
        ensureOpen();
        byte[] bson = encode(record);

        // Limit kontrolu her kayitta (milisaniye duzeyinde); tek belge limitten
        // buyukse yine kendi parcasina yazilir (BSON belge bolunemez).
        if (out != null && currentChunkBytes > 0
                && currentChunkBytes + bson.length > maxChunkBytes) {
            finishCurrentChunk();
            chunkIndex++;
        }

        if (out == null) {
            openChunk();
        }

        try {
            out.write(bson);
            currentChunkBytes += bson.length;
            recordCount++;
        } catch (IOException e) {
            throw new UncheckedIOException("BSON yazma hatasi: " + currentTmp, e);
        }
    }

    public long recordCount() {
        return recordCount;
    }

    public List<String> filesCreated() {
        return Collections.unmodifiableList(filesCreated);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // Bos koleksiyon: gereksiz/bos .bson dosyasi uretme.
        if (recordCount == 0 && out == null) {
            return;
        }
        finishCurrentChunk();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("BsonBackupWriter is closed");
    }

    private void openChunk() {
        try {
            Files.createDirectories(snapshotDir);
            String baseName = fileBaseName();
            currentTarget = snapshotDir.resolve(baseName + ".bson");
            currentTmp = snapshotDir.resolve(baseName + ".bson.tmp");
            if (Files.exists(currentTmp)) {
                Files.delete(currentTmp);
            }
            fileStream = new FileOutputStream(currentTmp.toFile());
            out = new BufferedOutputStream(fileStream, 64 * 1024);
            currentChunkBytes = 0;
        } catch (IOException e) {
            throw new UncheckedIOException("BSON parca acilamadi: " + currentTmp, e);
        }
    }

    private void finishCurrentChunk() {
        if (fileStream == null) return;
        try {
            out.flush();
            fileStream.getChannel().force(true); // fsync
            out.close();
            out = null;
            fileStream = null;

            Files.move(currentTmp, currentTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            filesCreated.add(currentTarget.getFileName().toString());
            currentTmp = null;
            currentTarget = null;
            currentChunkBytes = 0;
        } catch (IOException e) {
            throw new UncheckedIOException("BSON parca kapatilamadi / tasinamadi", e);
        }
    }

    private String fileBaseName() {
        String base = database + "_" + collection;
        if (chunkIndex == 0) return base;
        return base + "_" + chunkIndex;
    }

    private static byte[] encode(Map<String, Object> record) {
        Document document = new Document(record == null ? Map.of() : record);
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            CODEC.encode(writer, document, ENCODER_CONTEXT);
        }
        return buffer.toByteArray();
    }
}
