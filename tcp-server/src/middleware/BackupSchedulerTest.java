package middleware;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jsonparser.Json;
import middleware.storage.InMemoryStore;
import middleware.storage.Store;
import middleware.storage.backup.BackupScheduler;
import middleware.storage.backup.BackupService;

/**
 * Periyodik BackupScheduler dogruluk testleri (TCP gerektirmez).
 *
 *   java -cp "out:lib/*" middleware.BackupSchedulerTest
 */
public class BackupSchedulerTest {

    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        section("runCycle triggers backup");
        testRunCycleTriggersBackup();

        section("BackupEnabled=false skips backup");
        testDisabledSkipsBackup();

        section("concurrent second backup skipped");
        testConcurrentSkip();

        section("failed backup not marked successful");
        testFailedBackupNotSuccessful();

        section("retention count");
        testRetention();

        section("scheduler shutdown");
        testShutdown();

        section("interval config wiring");
        testIntervalConfig();

        System.out.println("\n==========================================");
        System.out.println(" RESULT: " + ok + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? " ALL SCHEDULER TESTS PASSED" : " SOME TESTS FAILED");
        System.out.println("==========================================");
        if (fail > 0) System.exit(1);
    }

    static void testRunCycleTriggersBackup() throws Exception {
        Path tmp = Files.createTempDirectory("sched-run");
        Store store = seedStore();
        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        BackupScheduler scheduler = new BackupScheduler(backup, store, true, 60, 24);

        BackupScheduler.RunResult result = scheduler.runCycle();
        check("runCycle SUCCESS", result == BackupScheduler.RunResult.SUCCESS);

        Path dbDir = tmp.resolve("okul");
        check("snapshot directory created", Files.isDirectory(dbDir));
        List<Path> snaps = listDirs(dbDir);
        check("at least one snapshot", !snaps.isEmpty());
        check("snapshot is successful", BackupService.isSuccessfulSnapshot(snaps.get(0)));

        Map<String, Object> manifest = Json.parseObject(
                Files.readString(snaps.get(0).resolve("manifest.json"), StandardCharsets.UTF_8));
        check("manifest success=true", Boolean.TRUE.equals(manifest.get("success")));
        check("manifest has snapshotId", manifest.get("snapshotId") != null);
        check("manifest has createdAt", manifest.get("createdAt") != null);

        scheduler.close();
    }

    static void testDisabledSkipsBackup() throws Exception {
        Path tmp = Files.createTempDirectory("sched-off");
        Store store = seedStore();
        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        BackupScheduler scheduler = new BackupScheduler(backup, store, false, 60, 24);

        BackupScheduler.RunResult result = scheduler.runCycle();
        check("disabled returns SKIPPED_DISABLED",
                result == BackupScheduler.RunResult.SKIPPED_DISABLED);
        check("no snapshot when disabled",
                !Files.exists(tmp.resolve("okul")) || listDirs(tmp.resolve("okul")).isEmpty());

        scheduler.start(); // should not schedule work
        Thread.sleep(50);
        check("still no snapshot after start() when disabled",
                !Files.exists(tmp.resolve("okul")) || listDirs(tmp.resolve("okul")).isEmpty());
        scheduler.close();
    }

    static void testConcurrentSkip() throws Exception {
        Path tmp = Files.createTempDirectory("sched-busy");
        Store store = seedStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger backupCalls = new AtomicInteger();

        BackupService slow = new BackupService(store, tmp.toString(), 1024 * 1024) {
            @Override
            public Map<String, Object> backupDatabase(String database) {
                backupCalls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return super.backupDatabase(database);
            }
        };

        BackupScheduler scheduler = new BackupScheduler(slow, store, true, 60, 24);
        Thread t = new Thread(scheduler::runCycle, "slow-backup");
        t.start();
        check("first cycle entered backup", entered.await(3, TimeUnit.SECONDS));

        BackupScheduler.RunResult second = scheduler.runCycle();
        check("second cycle SKIPPED_BUSY", second == BackupScheduler.RunResult.SKIPPED_BUSY);

        release.countDown();
        t.join(5000);
        check("slow backup finished", !t.isAlive());
        check("backupDatabase called once", backupCalls.get() == 1);
        scheduler.close();
    }

    static void testFailedBackupNotSuccessful() throws Exception {
        Path tmp = Files.createTempDirectory("sched-fail");
        Store store = seedStore();
        BackupService failing = new BackupService(store, tmp.toString(), 1024 * 1024) {
            @Override
            public Map<String, Object> backupDatabase(String database) {
                // Klasoru acip sonra patlat — cleanup basarisiz saymali.
                Path dir = backupRoot().resolve(database).resolve("broken_partial");
                try {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("orphan.tmp"), "x");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("simulated backup failure");
            }
        };

        // Gercek BackupService.backupDatabase cleanup'ini dogrudan da test et:
        BackupService real = new BackupService(store, tmp.toString(), 1024 * 1024);
        Store boom = new InMemoryStore() {
            @Override
            public void forEach(String collection, Map<String, Object> filter,
                                java.util.function.Consumer<Map<String, Object>> consumer) {
                throw new IllegalStateException("boom during stream");
            }

            @Override
            public List<String> listCollections(String database) {
                return List.of("kayitlar");
            }

            @Override
            public List<String> listDatabases() {
                return List.of("okul");
            }
        };
        BackupService streamingFail = new BackupService(boom, tmp.resolve("stream-fail").toString(), 1024);
        boolean threw = false;
        try {
            streamingFail.backupDatabase("okul");
        } catch (IllegalStateException e) {
            threw = e.getMessage() != null && e.getMessage().contains("boom");
        }
        check("streaming failure throws", threw);
        Path failDb = tmp.resolve("stream-fail").resolve("okul");
        check("failed snapshot cleaned up",
                !Files.exists(failDb) || listDirs(failDb).isEmpty());

        BackupScheduler scheduler = new BackupScheduler(failing, store, true, 60, 24);
        BackupScheduler.RunResult result = scheduler.runCycle();
        check("cycle FAILED", result == BackupScheduler.RunResult.FAILED);
        // broken_partial success manifest yok
        Path orphan = tmp.resolve("okul").resolve("broken_partial");
        check("orphan without success not a successful snapshot",
                !BackupService.isSuccessfulSnapshot(orphan));
        scheduler.close();
        // unused
        check("real backup service still usable", real.backupRoot() != null);
    }

    static void testRetention() throws Exception {
        Path tmp = Files.createTempDirectory("sched-ret");
        Store store = seedStore();
        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        BackupScheduler scheduler = new BackupScheduler(backup, store, true, 60, 2);

        for (int i = 0; i < 4; i++) {
            backup.backupDatabase("okul");
            Thread.sleep(2); // snapshotId carpismasin
        }
        Path dbDir = tmp.resolve("okul");
        check("created 4 snapshots before retention", listDirs(dbDir).size() == 4);

        // Basarisiz klasor — retention'a dahil edilmemeli, temizlenmeli.
        Path incomplete = dbDir.resolve("zzzz_incomplete");
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve("x.tmp"), "nope");

        int removed = scheduler.applyRetention("okul");
        List<Path> remaining = listDirs(dbDir);
        check("retention removed excess", removed >= 2);
        check("only 2 successful snapshots remain", remaining.size() == 2);
        for (Path p : remaining) {
            check("remaining is successful: " + p.getFileName(),
                    BackupService.isSuccessfulSnapshot(p));
        }
        check("incomplete snapshot removed", !Files.exists(incomplete));
        scheduler.close();
    }

    static void testShutdown() throws Exception {
        Path tmp = Files.createTempDirectory("sched-stop");
        Store store = seedStore();
        BackupService backup = new BackupService(store, tmp.toString(), 1024 * 1024);
        BackupScheduler scheduler = new BackupScheduler(backup, store, true, 60, 24);
        scheduler.start();
        scheduler.close();
        // Ikinci close guvenli olmali
        scheduler.close();
        check("shutdown completed without hang", true);
    }

    static void testIntervalConfig() {
        BackupScheduler s = new BackupScheduler(
                new BackupService(new InMemoryStore(), "backup-test-unused", 1024),
                new InMemoryStore(),
                true, 15, 10);
        check("intervalMinutes wired", s.intervalMinutes() == 15);
        check("retentionCount wired", s.retentionCount() == 10);
        check("enabled wired", s.enabled());
        s.close();
    }

    static Store seedStore() {
        Store store = new InMemoryStore();
        store.createCollection("okul", "kayitlar");
        store.insert("okul/kayitlar", Map.of("ad", "Ali"));
        return store;
    }

    static List<Path> listDirs(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(out::add);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    static void check(String name, boolean cond) {
        if (cond) { ok++; System.out.println("  [OK]   " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }
}
