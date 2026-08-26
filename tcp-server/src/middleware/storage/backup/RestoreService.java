package middleware.storage.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bson.BsonBinaryReader;
import org.bson.ByteBufNIO;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.bson.io.ByteBufferBsonInput;

import jsonparser.Json;
import middleware.storage.Store;

/**
 * Snapshot klasorundeki BSON parcalarini hedef Store koleksiyonlarina yukler.
 *
 * Politika:
 *   - Hedef veritabani BOS olmalidir (koleksiyon yok). Aksi halde reddedilir
 *     (duplicate restore / veri birlestirme yok).
 *   - id / createdAt / updatedAt korunur (Store.insertExact).
 *   - manifest.json varsa listedeki tum dosyalar mevcut olmali (eksik chunk reddi).
 *   - Parca indeksleri 0..N-1 arasinda bosluksuz olmali.
 *   - Hata durumunda bu restore'un yazdigi veriler dropDatabase ile geri alinir.
 */
public class RestoreService {

    private static final Pattern CHUNK_SUFFIX = Pattern.compile("^(.*)_(\\d+)$");
    private static final DocumentCodec CODEC = new DocumentCodec();
    private static final DecoderContext DECODER_CONTEXT = DecoderContext.builder().build();
    private static final int INSERT_BATCH_SIZE = 1000;

    private final Store store;

    public RestoreService(Store store) {
        if (store == null) throw new IllegalArgumentException("store is required");
        this.store = store;
    }

    /**
     * @param snapshotPath   ornek: backup/okul/20260826_143000_123
     * @param targetDatabase kayitlarin yazilacagi veritabani (bos olmali)
     */
    public Map<String, Object> restoreSnapshot(String snapshotPath, String targetDatabase) {
        if (snapshotPath == null || snapshotPath.isBlank()) {
            throw new IllegalArgumentException("snapshotPath is required");
        }
        if (targetDatabase == null || targetDatabase.isBlank()) {
            throw new IllegalArgumentException("targetDatabase is required");
        }

        Path snapshotDir = Paths.get(snapshotPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot dizini bulunamadi: " + snapshotDir);
        }

        // Duplicate restore politikasi: hedef bos degilse reddet.
        List<String> existing = store.listCollections(targetDatabase);
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Target database is not empty: " + targetDatabase
                            + " (collections=" + existing
                            + "). Drop it first or choose an empty targetDatabase.");
        }

        String sourceDatabase = resolveSourceDatabase(snapshotDir);
        Map<String, Object> manifest = readManifest(snapshotDir);
        List<Path> bsonFiles = resolveAndValidateFiles(snapshotDir, sourceDatabase, manifest);

        boolean started = false;
        try {
            long totalRecords = 0;
            long filesProcessed = 0;
            Map<String, Long> perCollection = new LinkedHashMap<>();
            List<String> restoredCollections = new ArrayList<>();

            if (manifest != null) {
                // Manifest sirasi: once bos koleksiyonlar, sonra dosyali olanlar.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cols =
                        (List<Map<String, Object>>) (List<?>) manifest.get("collections");
                if (cols != null) {
                    for (Map<String, Object> colMeta : cols) {
                        String collection = String.valueOf(colMeta.get("name"));
                        @SuppressWarnings("unchecked")
                        List<String> files = (List<String>) colMeta.get("files");
                        ensureCollection(targetDatabase, collection);
                        restoredCollections.add(collection);
                        started = true;

                        if (files == null || files.isEmpty()) {
                            perCollection.put(collection, 0L);
                            continue;
                        }

                        List<Map<String, Object>> batch = new ArrayList<>(INSERT_BATCH_SIZE);
                        long colCount = 0;
                        for (String fileName : files) {
                            Path file = snapshotDir.resolve(fileName);
                            long n = readBsonFile(file, batch, targetDatabase, collection);
                            colCount += n;
                            filesProcessed++;
                        }
                        flushBatch(targetDatabase, collection, batch);
                        totalRecords += colCount;
                        perCollection.put(collection, colCount);
                    }
                }
            } else {
                // Eski snapshot'lar (manifest yok): dosya listesinden ilerle.
                String currentCollection = null;
                List<Map<String, Object>> batch = new ArrayList<>(INSERT_BATCH_SIZE);

                for (Path file : bsonFiles) {
                    String fileName = file.getFileName().toString();
                    String collection = collectionFromFileName(fileName, sourceDatabase);
                    if (collection == null || collection.isBlank()) {
                        throw new IllegalArgumentException("Koleksiyon adi cozulemedi: " + fileName);
                    }

                    if (currentCollection != null && !currentCollection.equals(collection)) {
                        flushBatch(targetDatabase, currentCollection, batch);
                    }
                    if (!collection.equals(currentCollection)) {
                        currentCollection = collection;
                        if (!restoredCollections.contains(collection)) {
                            restoredCollections.add(collection);
                            ensureCollection(targetDatabase, collection);
                            started = true;
                        }
                    }

                    long fileRecords = readBsonFile(file, batch, targetDatabase, currentCollection);
                    totalRecords += fileRecords;
                    perCollection.merge(collection, fileRecords, Long::sum);
                    filesProcessed++;
                }

                if (currentCollection != null) {
                    flushBatch(targetDatabase, currentCollection, batch);
                }
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("snapshotPath", snapshotDir.toString());
            report.put("sourceDatabase", sourceDatabase);
            report.put("targetDatabase", targetDatabase);
            report.put("totalRecords", totalRecords);
            report.put("filesProcessed", filesProcessed);
            report.put("collections", restoredCollections);
            report.put("recordsPerCollection", perCollection);
            report.put("policy", "reject-if-target-not-empty");
            return report;
        } catch (RuntimeException e) {
            if (started) {
                try {
                    store.dropDatabase(targetDatabase);
                } catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        }
    }

    private static Map<String, Object> readManifest(Path snapshotDir) {
        Path file = snapshotDir.resolve(BackupService.MANIFEST_FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Json.parseObject(text);
        } catch (IOException e) {
            throw new UncheckedIOException("manifest.json okunamadi: " + file, e);
        } catch (Json.JsonException e) {
            throw new IllegalStateException("manifest.json bozuk: " + file + " — " + e.getMessage(), e);
        }
    }

    /**
     * Manifest'teki dosyalari dogrular; yoksa dizin listesi + parca surekliligi.
     */
    private static List<Path> resolveAndValidateFiles(Path snapshotDir, String sourceDatabase,
                                                      Map<String, Object> manifest) {
        if (manifest != null) {
            List<Path> ordered = new ArrayList<>();
            Set<String> expected = new LinkedHashSet<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols =
                    (List<Map<String, Object>>) (List<?>) manifest.get("collections");
            if (cols == null) {
                throw new IllegalStateException("manifest.json collections alani eksik");
            }
            for (Map<String, Object> colMeta : cols) {
                String collection = String.valueOf(colMeta.get("name"));
                @SuppressWarnings("unchecked")
                List<String> files = (List<String>) colMeta.get("files");
                if (files == null) files = List.of();

                // Parca indeksleri 0..n-1 bosluksuz olmali.
                validateContiguousChunks(files, sourceDatabase, collection);

                for (String fileName : files) {
                    if (!expected.add(fileName)) {
                        throw new IllegalStateException(
                                "manifest.json icinde tekrarlayan dosya: " + fileName);
                    }
                    Path p = snapshotDir.resolve(fileName);
                    if (!Files.isRegularFile(p)) {
                        throw new IllegalStateException(
                                "Incomplete snapshot: missing chunk file '" + fileName
                                        + "' for collection '" + collection + "'");
                    }
                    ordered.add(p);
                }
            }
            return ordered;
        }

        List<Path> files = listBsonFiles(snapshotDir);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Snapshot icinde .bson dosyasi yok: " + snapshotDir);
        }
        validateContiguousChunksForDirectory(files, sourceDatabase);
        return files;
    }

    private static void validateContiguousChunks(List<String> files, String sourceDatabase,
                                                 String collection) {
        if (files.isEmpty()) return;
        List<Integer> indices = new ArrayList<>();
        for (String fileName : files) {
            String parsed = collectionFromFileName(fileName, sourceDatabase);
            if (!collection.equals(parsed)) {
                throw new IllegalStateException(
                        "manifest dosya/koleksiyon uyusmazligi: " + fileName
                                + " (beklenen koleksiyon: " + collection + ")");
            }
            indices.add(chunkIndexFromFileName(fileName, sourceDatabase));
        }
        indices.sort(Integer::compareTo);
        for (int i = 0; i < indices.size(); i++) {
            if (indices.get(i) != i) {
                throw new IllegalStateException(
                        "Incomplete chunk sequence for collection '" + collection
                                + "': expected index " + i + ", found " + indices);
            }
        }
    }

    private static void validateContiguousChunksForDirectory(List<Path> files, String sourceDatabase) {
        Map<String, List<Integer>> byCollection = new LinkedHashMap<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            String col = collectionFromFileName(name, sourceDatabase);
            byCollection.computeIfAbsent(col, k -> new ArrayList<>())
                    .add(chunkIndexFromFileName(name, sourceDatabase));
        }
        for (Map.Entry<String, List<Integer>> e : byCollection.entrySet()) {
            List<Integer> indices = e.getValue();
            indices.sort(Integer::compareTo);
            for (int i = 0; i < indices.size(); i++) {
                if (indices.get(i) != i) {
                    throw new IllegalStateException(
                            "Incomplete chunk sequence for collection '" + e.getKey()
                                    + "': expected contiguous 0.." + (indices.size() - 1)
                                    + ", found " + indices);
                }
            }
        }
    }

    private static String resolveSourceDatabase(Path snapshotDir) {
        Path parent = snapshotDir.getParent();
        if (parent == null || parent.getFileName() == null) {
            throw new IllegalArgumentException(
                    "Snapshot yolu beklenen bicimde degil (backup/<db>/<tag>): " + snapshotDir);
        }
        return parent.getFileName().toString();
    }

    private static List<Path> listBsonFiles(Path snapshotDir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotDir, "*.bson")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && !p.getFileName().toString().endsWith(".tmp")) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Snapshot listelenemedi: " + snapshotDir, e);
        }
        files.sort(bsonFileOrder(resolveSourceDatabase(snapshotDir)));
        return files;
    }

    private static Comparator<Path> bsonFileOrder(String sourceDatabase) {
        return Comparator
                .comparing((Path p) -> collectionFromFileName(p.getFileName().toString(), sourceDatabase))
                .thenComparingInt(p -> chunkIndexFromFileName(p.getFileName().toString(), sourceDatabase));
    }

    static String collectionFromFileName(String fileName, String sourceDatabase) {
        String name = stripBsonExtension(fileName);
        String prefix = sourceDatabase + "_";
        String rest = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        Matcher chunk = CHUNK_SUFFIX.matcher(rest);
        if (chunk.matches()) {
            return chunk.group(1);
        }
        return rest;
    }

    static int chunkIndexFromFileName(String fileName, String sourceDatabase) {
        String name = stripBsonExtension(fileName);
        String prefix = sourceDatabase + "_";
        String rest = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        Matcher chunk = CHUNK_SUFFIX.matcher(rest);
        if (chunk.matches()) {
            try {
                return Integer.parseInt(chunk.group(2));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private static String stripBsonExtension(String fileName) {
        if (fileName.endsWith(".bson")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        return fileName;
    }

    private void ensureCollection(String database, String collection) {
        store.createCollection(database, collection);
    }

    private long readBsonFile(Path file, List<Map<String, Object>> batch,
                              String targetDatabase, String collection) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("BSON okunamadi: " + file, e);
        }
        if (bytes.length == 0) return 0;

        long count = 0;
        int offset = 0;
        try {
            while (offset + 4 <= bytes.length) {
                int length = (bytes[offset] & 0xff)
                        | ((bytes[offset + 1] & 0xff) << 8)
                        | ((bytes[offset + 2] & 0xff) << 16)
                        | ((bytes[offset + 3] & 0xff) << 24);
                if (length < 5 || offset + length > bytes.length) {
                    throw new IllegalStateException(
                            "Corrupt BSON in " + file.getFileName()
                                    + ": invalid document length " + length
                                    + " at offset " + offset);
                }

                ByteBuffer slice = ByteBuffer.wrap(bytes, offset, length);
                ByteBufferBsonInput input = new ByteBufferBsonInput(new ByteBufNIO(slice));
                try (BsonBinaryReader reader = new BsonBinaryReader(input)) {
                    Document doc = CODEC.decode(reader, DECODER_CONTEXT);
                    batch.add(toMap(doc));
                    count++;
                    if (batch.size() >= INSERT_BATCH_SIZE) {
                        flushBatch(targetDatabase, collection, batch);
                    }
                }
                offset += length;
            }
            if (offset != bytes.length) {
                throw new IllegalStateException(
                        "Corrupt BSON in " + file.getFileName()
                                + ": trailing " + (bytes.length - offset) + " byte(s)");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Corrupt BSON in " + file.getFileName() + ": " + e.getMessage(), e);
        }
        return count;
    }

    private void flushBatch(String database, String collection, List<Map<String, Object>> batch) {
        if (batch.isEmpty()) return;
        store.insertExact(Store.key(database, collection), new ArrayList<>(batch));
        batch.clear();
    }

    private static Map<String, Object> toMap(Document doc) {
        Map<String, Object> map = new LinkedHashMap<>(doc);
        map.remove("_id");
        return map;
    }
}
