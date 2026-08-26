package middleware;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jsonparser.Json;
import middleware.storage.InMemoryStore;
import middleware.storage.Store;
import middleware.storage.backup.BackupService;
import middleware.storage.backup.RestoreService;

/**
 * BSON backup/restore dogruluk testleri (TCP gerektirmez).
 *
 * Calistirma:
 *   java -cp "out:lib/*" middleware.BackupRestoreTest
 */
public class BackupRestoreTest {

    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        section("Chunking (1 KB)");
        testChunking();

        section("Empty collection");
        testEmptyCollection();

        section("ID / timestamp preservation");
        testIdAndTimestampPreservation();

        section("Duplicate restore policy");
        testDuplicateRestore();

        section("Corrupt BSON");
        testCorruptBson();

        section("Incomplete chunk");
        testIncompleteChunk();

        section("Millisecond snapshot tag");
        testMillisecondSnapshotTag();

        System.out.println("\n==========================================");
        System.out.println(" RESULT: " + ok + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? " ALL BACKUP RESTORE TESTS PASSED" : " SOME TESTS FAILED");
        System.out.println("==========================================");
        if (fail > 0) System.exit(1);
    }

    static void testChunking() throws Exception {
        Path tmp = Files.createTempDirectory("bson-chunk");
        Store store = new InMemoryStore();
        store.createCollection("okul", "ogrenciler");
        for (int i = 0; i < 40; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", "Ogrenci-" + i);
            r.put("payload", "x".repeat(80));
            store.insert("okul/ogrenciler", r);
        }

        BackupService backup = new BackupService(store, tmp.toString(), 1024);
        Map<String, Object> report = backup.backupDatabase("okul");
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) report.get("filesCreated");
        check("chunking produced multiple files", files != null && files.size() >= 3);
        check("first chunk has no numeric suffix",
                files != null && files.get(0).equals("okul_ogrenciler.bson"));
        check("second chunk is _1.bson",
                files != null && files.contains("okul_ogrenciler_1.bson"));
        check("third chunk is _2.bson",
                files != null && files.contains("okul_ogrenciler_2.bson"));
        check("manifest.json exists",
                Files.isRegularFile(Path.of((String) report.get("path")).resolve("manifest.json")));
    }

    static void testEmptyCollection() throws Exception {
        Path tmp = Files.createTempDirectory("bson-empty");
        Store store = new InMemoryStore();
        store.createCollection("okul", "dolu");
        store.createCollection("okul", "bos");
        store.insert("okul/dolu", Map.of("ad", "Ali"));

        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        Map<String, Object> report = backup.backupDatabase("okul");
        Path snapshot = Path.of((String) report.get("path"));

        check("empty collection creates no .bson file",
                !Files.exists(snapshot.resolve("okul_bos.bson")));
        check("non-empty collection creates .bson file",
                Files.isRegularFile(snapshot.resolve("okul_dolu.bson")));

        Map<String, Object> manifest = Json.parseObject(
                Files.readString(snapshot.resolve("manifest.json")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) (List<?>) manifest.get("collections");
        Map<String, Object> bosMeta = cols.stream()
                .filter(c -> "bos".equals(c.get("name")))
                .findFirst().orElse(null);
        check("manifest lists empty collection", bosMeta != null);
        check("empty collection has zero records in manifest",
                bosMeta != null && ((Number) bosMeta.get("recordCount")).longValue() == 0);
        check("empty collection has empty files list",
                bosMeta != null && bosMeta.get("files") instanceof List<?> f && f.isEmpty());

        Store target = new InMemoryStore();
        RestoreService restore = new RestoreService(target);
        restore.restoreSnapshot(snapshot.toString(), "okul2");
        check("empty collection recreated on restore",
                target.listCollections("okul2").contains("bos"));
        check("empty collection has zero rows after restore",
                target.find("okul2/bos", null).isEmpty());
        check("non-empty collection restored",
                target.find("okul2/dolu", null).size() == 1);
    }

    static void testIdAndTimestampPreservation() throws Exception {
        Path tmp = Files.createTempDirectory("bson-preserve");
        Store store = new InMemoryStore();
        store.createCollection("okul", "kayitlar");
        Map<String, Object> inserted = store.insert("okul/kayitlar", Map.of("ad", "Ayse"));
        String id = String.valueOf(inserted.get("id"));
        String created = String.valueOf(inserted.get("createdAt"));
        String updated = String.valueOf(inserted.get("updatedAt"));

        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        Map<String, Object> report = backup.backupDatabase("okul");

        Store target = new InMemoryStore();
        new RestoreService(target).restoreSnapshot((String) report.get("path"), "okul");
        List<Map<String, Object>> rows = target.find("okul/kayitlar", null);
        check("one record restored", rows.size() == 1);
        Map<String, Object> row = rows.get(0);
        check("id preserved", id.equals(String.valueOf(row.get("id"))));
        check("createdAt preserved", created.equals(String.valueOf(row.get("createdAt"))));
        check("updatedAt preserved", updated.equals(String.valueOf(row.get("updatedAt"))));
        check("ad preserved", "Ayse".equals(String.valueOf(row.get("ad"))));
    }

    static void testDuplicateRestore() throws Exception {
        Path tmp = Files.createTempDirectory("bson-dup");
        Store store = new InMemoryStore();
        store.createCollection("okul", "kayitlar");
        store.insert("okul/kayitlar", Map.of("ad", "Ali"));
        Map<String, Object> report = new BackupService(store, tmp.toString(), 1024 * 1024)
                .backupDatabase("okul");

        Store target = new InMemoryStore();
        RestoreService restore = new RestoreService(target);
        restore.restoreSnapshot((String) report.get("path"), "hedef");
        check("first restore ok", target.find("hedef/kayitlar", null).size() == 1);

        boolean rejected = false;
        try {
            restore.restoreSnapshot((String) report.get("path"), "hedef");
        } catch (IllegalStateException e) {
            rejected = e.getMessage() != null && e.getMessage().contains("not empty");
        }
        check("second restore rejected", rejected);
        check("no duplicate rows after rejected restore",
                target.find("hedef/kayitlar", null).size() == 1);
    }

    static void testCorruptBson() throws Exception {
        Path tmp = Files.createTempDirectory("bson-corrupt");
        Store store = new InMemoryStore();
        store.createCollection("okul", "kayitlar");
        store.insert("okul/kayitlar", Map.of("ad", "Ali"));
        store.insert("okul/kayitlar", Map.of("ad", "Veli"));
        Map<String, Object> report = new BackupService(store, tmp.toString(), 1024 * 1024)
                .backupDatabase("okul");
        Path snapshot = Path.of((String) report.get("path"));
        Path bson = snapshot.resolve("okul_kayitlar.bson");
        // Belge uzunlugunu abartisal yap: okuyucu corrupt olarak reddetsin.
        byte[] original = Files.readAllBytes(bson);
        byte[] broken = original.clone();
        broken[0] = (byte) 0xFF;
        broken[1] = (byte) 0xFF;
        broken[2] = (byte) 0x00;
        broken[3] = (byte) 0x00;
        Files.write(bson, broken);

        Store target = new InMemoryStore();
        RestoreService restore = new RestoreService(target);
        boolean failed = false;
        String message = null;
        try {
            restore.restoreSnapshot(snapshot.toString(), "hedef");
        } catch (RuntimeException e) {
            failed = true;
            message = e.getMessage();
        }
        check("corrupt BSON restore fails", failed);
        check("error mentions corrupt/invalid BSON",
                message != null && (message.toLowerCase().contains("corrupt")
                        || message.toLowerCase().contains("invalid")
                        || message.toLowerCase().contains("bson")));
        check("target remains empty after corrupt restore",
                target.listCollections("hedef").isEmpty());
    }

    static void testIncompleteChunk() throws Exception {
        Path tmp = Files.createTempDirectory("bson-incomplete");
        Store store = new InMemoryStore();
        store.createCollection("okul", "ogrenciler");
        for (int i = 0; i < 30; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", "O-" + i);
            r.put("payload", "y".repeat(100));
            store.insert("okul/ogrenciler", r);
        }
        Map<String, Object> report = new BackupService(store, tmp.toString(), 1024)
                .backupDatabase("okul");
        Path snapshot = Path.of((String) report.get("path"));
        @SuppressWarnings("unchecked")
        List<String> files = new ArrayList<>((List<String>) report.get("filesCreated"));
        check("incomplete-test precondition: >= 3 chunks", files.size() >= 3);

        Path lastChunk = snapshot.resolve(files.get(files.size() - 1));
        Files.delete(lastChunk);

        Store target = new InMemoryStore();
        boolean failed = false;
        String message = null;
        try {
            new RestoreService(target).restoreSnapshot(snapshot.toString(), "hedef");
        } catch (RuntimeException e) {
            failed = true;
            message = e.getMessage();
        }
        check("incomplete chunk restore fails", failed);
        check("error mentions missing/incomplete chunk",
                message != null && (message.contains("Incomplete") || message.contains("missing")));
        check("target remains empty after incomplete restore",
                target.listCollections("hedef").isEmpty());
    }

    static void testMillisecondSnapshotTag() throws Exception {
        Path tmp = Files.createTempDirectory("bson-ms");
        Store store = new InMemoryStore();
        store.createCollection("okul", "x");
        store.insert("okul/x", Map.of("n", 1));
        Map<String, Object> a = new BackupService(store, tmp.toString(), 1024 * 1024)
                .backupDatabase("okul");
        Thread.sleep(2);
        Map<String, Object> b = new BackupService(store, tmp.toString(), 1024 * 1024)
                .backupDatabase("okul");
        String tagA = String.valueOf(a.get("snapshotTag"));
        String tagB = String.valueOf(b.get("snapshotTag"));
        check("snapshotTag matches ms pattern",
                tagA.matches("\\d{8}_\\d{6}_\\d{3}.*"));
        check("two rapid backups get distinct snapshot tags", !tagA.equals(tagB));
    }

    static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    static void check(String name, boolean cond) {
        if (cond) { ok++; System.out.println("  [OK]   " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }
}
