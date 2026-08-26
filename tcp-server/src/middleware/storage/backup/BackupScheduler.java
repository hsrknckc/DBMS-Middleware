package middleware.storage.backup;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import middleware.config.AppConfig;
import middleware.storage.Store;

/**
 * Periyodik BSON snapshot zamanlayicisi.
 *
 * Sorumluluklar:
 *   - ne zaman backup alinacagi
 *   - ayni anda tek backup (thread-safe)
 *   - retention (eski basarili snapshot'lari budama)
 *
 * Snapshot uretimi BackupService'e aittir. CRUD islemlerini kilitlemez.
 */
public class BackupScheduler implements AutoCloseable {

    /** Ust veri / sistem veritabanlari yedeklenmez. */
    private static final List<String> SKIP_DATABASES = List.of("__meta__", "admin", "local", "config");

    public enum RunResult {
        SUCCESS,
        SKIPPED_BUSY,
        SKIPPED_DISABLED,
        FAILED
    }

    private final BackupService backupService;
    private final Store store;
    private final boolean enabled;
    private final long intervalMinutes;
    private final int retentionCount;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> future;
    private volatile boolean started = false;

    public BackupScheduler(BackupService backupService, Store store, AppConfig config) {
        this(backupService, store,
                config == null || config.backupEnabled(),
                config == null ? 60 : config.backupIntervalMinutes(),
                config == null ? 24 : config.backupRetentionCount());
    }

    public BackupScheduler(BackupService backupService, Store store,
                           boolean enabled, int intervalMinutes, int retentionCount) {
        if (backupService == null) throw new IllegalArgumentException("backupService is required");
        if (store == null) throw new IllegalArgumentException("store is required");
        this.backupService = backupService;
        this.store = store;
        this.enabled = enabled;
        this.intervalMinutes = Math.max(1, intervalMinutes);
        this.retentionCount = Math.max(1, retentionCount);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean enabled() {
        return enabled;
    }

    public long intervalMinutes() {
        return intervalMinutes;
    }

    public int retentionCount() {
        return retentionCount;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Periyodik gorevi baslatir. BackupEnabled=false ise sadece log yazar.
     * Ilk calisma {@code intervalMinutes} sonra; hemen almak icin {@link #runCycle()}.
     */
    public synchronized void start() {
        if (started) return;
        started = true;
        if (!enabled) {
            System.out.println("[BACKUP] Scheduler disabled (BackupEnabled=false)");
            return;
        }
        System.out.println("[BACKUP] Scheduler started (interval=" + intervalMinutes
                + " min, retention=" + retentionCount + ")");
        future = executor.scheduleWithFixedDelay(
                this::safeRunCycle,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES);
    }

    /** Test / elle tetikleme: bir backup dongusu calistirir. */
    public RunResult runCycle() {
        if (!enabled) {
            System.out.println("[BACKUP] Skipped: scheduler disabled");
            return RunResult.SKIPPED_DISABLED;
        }
        if (!running.compareAndSet(false, true)) {
            System.out.println("[BACKUP] Skipped: previous backup still running");
            return RunResult.SKIPPED_BUSY;
        }
        try {
            return doRunCycle();
        } finally {
            running.set(false);
        }
    }

    private void safeRunCycle() {
        try {
            runCycle();
        } catch (Throwable t) {
            System.out.println("[BACKUP] FAILED");
            System.out.println("[BACKUP] Reason: " + t.getMessage());
        }
    }

    private RunResult doRunCycle() {
        System.out.println("[BACKUP] Started: " + java.time.LocalDateTime.now());
        try {
            List<String> databases = activeDatabases();
            if (databases.isEmpty()) {
                System.out.println("[BACKUP] No user databases to back up");
                System.out.println("[BACKUP] Completed successfully");
                return RunResult.SUCCESS;
            }

            for (String database : databases) {
                Map<String, Object> report = backupService.backupDatabase(database);
                Object chunks = report.get("fileCount");
                System.out.println("[BACKUP] Database done: " + database
                        + " (files=" + chunks
                        + ", records=" + report.get("totalRecords") + ")");
                applyRetention(database);
            }

            System.out.println("[BACKUP] Completed successfully");
            return RunResult.SUCCESS;
        } catch (RuntimeException e) {
            System.out.println("[BACKUP] FAILED");
            System.out.println("[BACKUP] Reason: " + e.getMessage());
            return RunResult.FAILED;
        }
    }

    private List<String> activeDatabases() {
        List<String> names = new ArrayList<>();
        for (String name : store.listDatabases()) {
            if (name == null || name.isBlank()) continue;
            if (SKIP_DATABASES.contains(name)) continue;
            names.add(name);
        }
        return names;
    }

    /**
     * Veritabani altindaki basarili snapshot'lardan yalnizca son
     * {@code retentionCount} kadarini birakir; eskileri siler.
     * Basarisiz/yarim klasorler (success manifest yok) retention'a dahil
     * edilmez ve temizlenir.
     */
    public int applyRetention(String database) {
        Path dbDir = backupService.backupRoot().resolve(database);
        if (!Files.isDirectory(dbDir)) return 0;

        List<Path> successful = new ArrayList<>();
        List<Path> incomplete = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                if (BackupService.isSuccessfulSnapshot(child)) {
                    successful.add(child);
                } else {
                    incomplete.add(child);
                }
            }
        } catch (IOException e) {
            System.out.println("[BACKUP] UYARI: retention listelenemedi: " + e.getMessage());
            return 0;
        }

        // Yarim / basarisiz klasorleri temizle (retention hesabina girmez).
        for (Path bad : incomplete) {
            System.out.println("[BACKUP] Removing incomplete snapshot: " + bad.getFileName());
            BackupService.deleteRecursivelyQuiet(bad);
        }

        successful.sort(Comparator.comparing(p -> p.getFileName().toString()));
        int removed = 0;
        int excess = successful.size() - retentionCount;
        for (int i = 0; i < excess; i++) {
            Path old = successful.get(i);
            System.out.println("[BACKUP] Retention delete: " + database + "/" + old.getFileName());
            BackupService.deleteRecursivelyQuiet(old);
            removed++;
        }
        return removed;
    }

    @Override
    public synchronized void close() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        started = false;
        System.out.println("[BACKUP] Scheduler stopped");
    }
}
