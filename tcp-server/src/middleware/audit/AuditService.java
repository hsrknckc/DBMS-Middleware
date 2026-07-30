package middleware.audit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import middleware.storage.Store;

/**
 * DENETIM KAYITLARI (audit log)
 *
 * Sistemde yapilan degisiklikleri kaydeder: kim, ne zaman, neyi degistirdi,
 * eski ve yeni degerler neydi. On Yuz'un "Audit Logs" ekrani bunlari
 * listeler ve bazi islemleri geri almayi teklif eder.
 *
 * Kayitlar "__meta__/audit" koleksiyonunda tutulur; MongoDB kullaniliyorsa
 * kalicidir.
 *
 * GERI ALMA
 * Her kayit, gerektiginde islemi tersine cevirecek bilgiyi (undo) tasir.
 * Ornegin bir kullanicinin yetkileri degistirildiginde eski yetki listesi
 * saklanir; geri alindiginda o liste yeniden yazilir.
 * Yalnizca tersine cevrilebilir islemler "revertible" isaretlenir.
 */
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
     * Yeni bir denetim kaydi olusturur.
     *
     * @param actionCode  On Yuz'un tanidigi kod (userCreated, permissionsUpdated...)
     * @param performedBy islemi yapan kullanici
     * @param targetId    islemin uygulandigi nesne (kullanici id'si vb.), olabilir null
     * @param targetName  okunabilir hedef adi
     * @param description insan okunur aciklama
     * @param oldValues   degisiklikten onceki degerler (geri alma icin)
     * @param newValues   degisiklikten sonraki degerler
     * @param revertible  bu islem geri alinabilir mi
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
        entry.put("description", description == null ? "" : description);
        entry.put("oldValues", oldValues == null ? Map.of() : oldValues);
        entry.put("newValues", newValues == null ? Map.of() : newValues);
        entry.put("isRevertible", revertible);
        entry.put("isReverted", false);
        entry.put("revertedAt", null);
        entry.put("revertedByName", null);
        entry.put("occurredAt", now());

        try {
            return store.insert(COLLECTION, entry);
        } catch (Exception e) {
            // Denetim kaydi yazilamazsa asil islem bozulmamali; sadece uyar.
            System.out.println("[audit] UYARI: kayit yazilamadi: " + e.getMessage());
            return entry;
        }
    }

    /**
     * Kayitlari doner, en yeniden eskiye siralanmis.
     *
     * @param actionCode      yalnizca bu action; null ise hepsi
     * @param onlyRevertible  yalnizca geri alinabilir ve henuz alinmamis olanlar
     * @param limit           en fazla kac kayit (0 = sinirsiz)
     */
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

        // En yeni once: zaman damgasi metin olarak ISO oldugundan dogrudan siralanabilir
        result.sort(Comparator.comparing(
                (Map<String, Object> m) -> String.valueOf(m.getOrDefault("occurredAt", "")))
                .reversed());

        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(0, limit));
        }
        return result;
    }

    /** Tek bir kaydi id ile bulur. */
    public Map<String, Object> byId(String id) {
        List<Map<String, Object>> found = store.find(COLLECTION, Map.of("id", id));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Kaydi "geri alindi" olarak isaretler. */
    public Map<String, Object> markReverted(String id, String revertedByName) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isReverted", true);
        patch.put("revertedAt", now());
        patch.put("revertedByName", revertedByName);
        return store.updateById(COLLECTION, id, patch);
    }

    /**
     * On Yuz'un panosundaki "son etkinlikler" listesi.
     * Denetim kayitlarini daha sade bir bicime cevirir.
     */
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
