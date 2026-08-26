package middleware.storage.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jsonparser.Json;
import middleware.config.AppConfig;
import middleware.storage.Store;

/**
 * Veritabani BSON yedekleme servisi (tek seferlik snapshot uretimi).
 *
 * Snapshot dizini (mevcut restore uyumu):
 *   backup/&lt;database&gt;/&lt;yyyyMMdd_HHmmss_SSS&gt;/
 *
 * Periyodik tetikleme BackupScheduler sorumlulugundadir.
 * Hata durumunda yarim snapshot silinir; yalnizca success=true manifest
 * basarili yedek sayilir.
 */
public class BackupService {

    /** Milisaniye hassasiyetinde benzersiz snapshot etiketi. */
    static final DateTimeFormatter SNAPSHOT_TAG =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    static final String MANIFEST_FILE = "manifest.json";

    private final Store store;
    private final Path backupRoot;
    private final long maxChunkBytes;

    public BackupService(Store store, AppConfig config) {
        this(store,
                config == null ? "backup" : config.backupDirectory(),
                config == null ? 100L * 1024L * 1024L : config.maxChunkSizeBytes());
    }

    public BackupService(Store store, String backupDirectory, long maxChunkBytes) {
        if (store == null) throw new IllegalArgumentException("store is required");
        String dir = (backupDirectory == null || backupDirectory.isBlank())
                ? "backup" : backupDirectory;
        this.store = store;
        this.backupRoot = Paths.get(dir).toAbsolutePath().normalize();
        this.maxChunkBytes = maxChunkBytes < 1 ? 100L * 1024L * 1024L : maxChunkBytes;
    }

    public Path backupRoot() {
        return backupRoot;
    }

    public long maxChunkBytes() {
        return maxChunkBytes;
    }

    /**
     * Veritabanindaki tum koleksiyonlari snapshot klasorune parcali BSON olarak yazar.
     * Basarisiz olursa olusturulan dizin temizlenir.
     *
     * @return ozet rapor (success=true)
     */
    public Map<String, Object> backupDatabase(String database) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("database is required");
        }

        String snapshotId = LocalDateTime.now().format(SNAPSHOT_TAG);
        Path snapshotDir = backupRoot.resolve(database).resolve(snapshotId);

        int collision = 0;
        while (Files.exists(snapshotDir)) {
            collision++;
            snapshotId = LocalDateTime.now().format(SNAPSHOT_TAG) + "_" + collision;
            snapshotDir = backupRoot.resolve(database).resolve(snapshotId);
        }

        System.out.println("[BACKUP] Database: " + database);
        System.out.println("[BACKUP] Snapshot: " + snapshotId);

        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Snapshot dizini olusturulamadi: " + snapshotDir, e);
        }

        try {
            List<String> collections = store.listCollections(database);
            long totalRecords = 0;
            List<String> filesCreated = new ArrayList<>();
            Map<String, Long> perCollection = new LinkedHashMap<>();
            List<Map<String, Object>> manifestCollections = new ArrayList<>();
            String createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

            for (String collection : collections) {
                System.out.println("[BACKUP] Collection: " + collection);
                String storeKey = Store.key(database, collection);
                BsonBackupWriter writer = new BsonBackupWriter(
                        snapshotDir, database, collection, maxChunkBytes);
                try {
                    store.forEach(storeKey, null, writer::write);
                } finally {
                    writer.close();
                }
                totalRecords += writer.recordCount();
                perCollection.put(collection, writer.recordCount());
                filesCreated.addAll(writer.filesCreated());
                System.out.println("[BACKUP] Chunks: " + writer.filesCreated().size()
                        + " (records=" + writer.recordCount() + ")");

                Map<String, Object> colMeta = new LinkedHashMap<>();
                colMeta.put("name", collection);
                colMeta.put("recordCount", writer.recordCount());
                colMeta.put("files", new ArrayList<>(writer.filesCreated()));
                manifestCollections.add(colMeta);
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("version", 1L);
            manifest.put("snapshotId", snapshotId);
            manifest.put("snapshotTag", snapshotId); // geriye donuk uyum
            manifest.put("createdAt", createdAt);
            manifest.put("database", database);
            manifest.put("success", true);
            manifest.put("totalRecords", totalRecords);
            manifest.put("maxChunkBytes", maxChunkBytes);
            manifest.put("collections", manifestCollections);
            writeManifest(snapshotDir, manifest);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("database", database);
            report.put("snapshotId", snapshotId);
            report.put("snapshotTag", snapshotId);
            report.put("createdAt", createdAt);
            report.put("path", snapshotDir.toString());
            report.put("totalRecords", totalRecords);
            report.put("collectionCount", (long) collections.size());
            report.put("filesCreated", filesCreated);
            report.put("fileCount", (long) filesCreated.size());
            report.put("recordsPerCollection", perCollection);
            report.put("maxChunkBytes", maxChunkBytes);
            report.put("manifest", MANIFEST_FILE);
            report.put("success", true);
            return report;
        } catch (RuntimeException e) {
            System.out.println("[BACKUP] FAILED");
            System.out.println("[BACKUP] Reason: " + e.getMessage());
            deleteRecursivelyQuiet(snapshotDir);
            throw e;
        }
    }

    /**
     * Snapshot klasorunun basarili tamamlanmis bir yedek olup olmadigini kontrol eder.
     * manifest.json yoksa veya success != true ise false.
     */
    public static boolean isSuccessfulSnapshot(Path snapshotDir) {
        Path manifestFile = snapshotDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestFile)) return false;
        try {
            Map<String, Object> manifest = Json.parseObject(
                    Files.readString(manifestFile, StandardCharsets.UTF_8));
            return Boolean.TRUE.equals(manifest.get("success"));
        } catch (Exception e) {
            return false;
        }
    }

    static void deleteRecursivelyQuiet(Path root) {
        if (root == null || !Files.exists(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            System.out.println("[BACKUP] UYARI: yarim snapshot silinemedi: " + root);
        }
    }

    private static void writeManifest(Path snapshotDir, Map<String, Object> manifest) {
        Path file = snapshotDir.resolve(MANIFEST_FILE);
        try {
            Files.writeString(file, Json.stringify(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("manifest.json yazilamadi: " + file, e);
        }
    }
}
