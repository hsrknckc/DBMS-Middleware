package middleware.audit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import middleware.storage.Store;

public class AuditService {

    public static final String COLLECTION = "__meta__/audit";

    private final Store store;

    public AuditService(Store store) {
        this.store = store;
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * Kullanici yonetimi denetim kaydi (Geriye donuk uyumluluk icin).
     */
    public Map<String, Object> record(String actionCode,
                                      String performedById, String performedByName,
                                      String targetId, String targetName,
                                      String description,
                                      Map<String, Object> oldValues,
                                      Map<String, Object> newValues,
                                      boolean revertible) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", actionCode);
        entry.put("performedById", performedById == null ? "" : performedById);
        entry.put("performedByName", performedByName == null ? "" : performedByName);
        entry.put("targetUserId", targetId);
        entry.put("targetUserName", targetName);
        entry.put("targetDatabase", null);
        entry.put("targetCollection", null);
        entry.put("targetRecordId", null);
        entry.put("description", description == null ? "" : description);
        entry.put("oldValues", oldValues);
        entry.put("newValues", newValues);
        entry.put("isRevertible", revertible);
        entry.put("isReverted", false);
        entry.put("revertedAt", null);
        entry.put("revertedById", null);
        entry.put("revertedByName", null);
        entry.put("occurredAt", now());

        return saveEntry(entry);
    }

    /**
     * CRUD veri degisiklikleri icin ayrintili denetim kaydi.
     */
    public Map<String, Object> recordDataAction(String actionCode,
                                                String performedById, String performedByName,
                                                String database, String collection, String recordId,
                                                String description,
                                                Map<String, Object> oldValues,
                                                Map<String, Object> newValues,
                                                boolean revertible) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", actionCode);
        entry.put("performedById", performedById == null ? "" : performedById);
        entry.put("performedByName", performedByName == null ? "" : performedByName);
        entry.put("targetUserId", null);
        entry.put("targetUserName", null);
        entry.put("targetDatabase", database);
        entry.put("targetCollection", collection);
        entry.put("targetRecordId", recordId);
        entry.put("description", description == null ? "" : description);
        entry.put("oldValues", oldValues);
        entry.put("newValues", newValues);
        entry.put("isRevertible", revertible);
        entry.put("isReverted", false);
        entry.put("revertedAt", null);
        entry.put("revertedById", null);
        entry.put("revertedByName", null);
        entry.put("occurredAt", now());

        return saveEntry(entry);
    }

    private Map<String, Object> saveEntry(Map<String, Object> entry) {
        return store.insert(COLLECTION, entry);
    }

    public List<Map<String, Object>> list(String actionCode, Boolean onlyRevertible, int limit) {
        List<Map<String, Object>> all = store.find(COLLECTION, null);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> log : all) {
            if (actionCode != null && !actionCode.equals(log.get("action"))) continue;
            if (Boolean.TRUE.equals(onlyRevertible)) {
                boolean revertible = Boolean.TRUE.equals(log.get("isRevertible"));
                boolean reverted = Boolean.TRUE.equals(log.get("isReverted"));
                if (!revertible || reverted) continue;
            }
            result.add(log);
        }

        result.sort(Comparator.comparing(
                (Map<String, Object> m) -> String.valueOf(m.getOrDefault("occurredAt", "")))
                .reversed());

        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    public Map<String, Object> byId(String id) {
        List<Map<String, Object>> found = store.find(COLLECTION, Map.of("id", id));
        return found.isEmpty() ? null : found.get(0);
    }

    public Map<String, Object> markReverted(String id, String revertedById, String revertedByName) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isReverted", true);
        patch.put("revertedAt", now());
        patch.put("revertedById", revertedById == null ? "" : revertedById);
        patch.put("revertedByName", revertedByName == null ? "" : revertedByName);
        return store.updateById(COLLECTION, id, patch);
    }

    public List<Map<String, Object>> recentActivities(int limit) {
        List<Map<String, Object>> logs = list(null, null, limit);
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> log : logs) {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("title", String.valueOf(log.getOrDefault("action", "")));
            activity.put("description", String.valueOf(log.getOrDefault("description", "")));
            activity.put("occurredAt", log.get("occurredAt"));
            activity.put("actionType", String.valueOf(log.getOrDefault("action", "")));
            result.add(activity);
        }
        return result;
    }
}