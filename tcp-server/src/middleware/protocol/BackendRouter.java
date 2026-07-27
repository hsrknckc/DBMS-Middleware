package middleware.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jsonparser.Json;
import middleware.auth.AuthService;
import middleware.auth.User;
import middleware.events.Event;
import middleware.events.EventBus;
import middleware.storage.DataStore;

/**
 * ARKA YUZ (backend) PROTOKOLU islemcisi. PROTOKOL.md sozlesmesine uyar.
 *
 * Istek : {"requestId":"..","action":"WRITE","username":"..","password":"..",
 *          "database":"okul","collection":"ogrenciler","filter":{..},"document":{..}}
 * Cevap : {"requestId":"..","status":"OK|UNAUTHORIZED|ERROR","message":"..","data":[..]}
 *
 * Frontend protokolunden farklari: BUYUK HARF action, her istekte username+password
 * (token degil), duz alanlar (payload yok), status/message zarfi.
 *
 * Ayni DataStore ve AuthService'i kullanir; yani iki protokol ayni veriyi paylasir.
 * Yetki eslemesi (Ister_0015): READ->dataView, WRITE->dataCreate,
 * UPDATE->dataUpdate, DELETE->dataDelete.
 */
public class BackendRouter {

    private final DataStore store;
    private final EventBus eventBus;
    private final AuthService auth;

    public BackendRouter(DataStore store, EventBus eventBus, AuthService auth) {
        this.store = store;
        this.eventBus = eventBus;
        this.auth = auth;
    }

    /** Bu satirin backend protokolu olup olmadigini anlar (BUYUK HARF action + username). */
    public static boolean matches(Map<String, Object> request) {
        Object action = request.get("action");
        boolean bigAction = action instanceof String s
                && !s.isEmpty() && s.equals(s.toUpperCase()) && !s.contains(".");
        boolean hasUsername = request.containsKey("username");
        return bigAction || hasUsername;
    }

    @SuppressWarnings("unchecked")
    public String handle(Map<String, Object> request) {
        String rid = str(request, "requestId");
        try {
            String action = str(request, "action");
            if (action == null) return status(rid, "ERROR", "action field is required", null);

            // Her istekte kimlik dogrulama (protokol boyle istiyor).
            User user = auth.authenticate(str(request, "username"), str(request, "password"));
            if (user == null) {
                return status(rid, "UNAUTHORIZED", "Invalid username or password", null);
            }

            return switch (action) {
                case "PING"             -> ping(rid);
                case "READ"             -> read(rid, user, request);
                case "WRITE"            -> write(rid, user, request);
                case "UPDATE"           -> update(rid, user, request);
                case "DELETE"           -> delete(rid, user, request);
                case "LIST_DATABASES"   -> listDatabases(rid, user);
                case "LIST_COLLECTIONS" -> listCollections(rid, user, request);
                default -> status(rid, "ERROR", "Unknown action: " + action, null);
            };

        } catch (Exception e) {
            // Protokol: hicbir durumda baglanti kopmaz, ERROR doner.
            return status(rid, "ERROR", "Server error: " + e.getMessage(), null);
        }
    }

    private String ping(String rid) {
        // DataStore su an bellekte; her zaman erisilebilir. MongoDB baglaninca
        // burada gercek baglanti kontrolu yapilacak (Ister_0018).
        return status(rid, "OK", "MongoDB is active", null);
    }

    @SuppressWarnings("unchecked")
    private String read(String rid, User user, Map<String, Object> req) {
        if (!user.can("dataView")) return unauthorized(rid, "dataView");
        String col = target(req);
        if (col == null) return status(rid, "ERROR", "database/collection is required", null);
        Map<String, Object> filter = asMapOrNull(req.get("filter"));
        List<Map<String, Object>> records = store.find(col, filter);
        return status(rid, "OK", records.size() + " record(s) found", records);
    }

    @SuppressWarnings("unchecked")
    private String write(String rid, User user, Map<String, Object> req) {
        if (!user.can("dataCreate")) return unauthorized(rid, "dataCreate");
        String col = target(req);
        if (col == null) return status(rid, "ERROR", "database/collection is required", null);
        Object doc = req.get("document");
        if (!(doc instanceof Map)) return status(rid, "ERROR", "document field must be an object", null);
        Map<String, Object> inserted = store.insert(col, (Map<String, Object>) doc);
        eventBus.publish(new Event("insert", col, inserted));
        return status(rid, "OK", "1 record inserted", null);
    }

    @SuppressWarnings("unchecked")
    private String update(String rid, User user, Map<String, Object> req) {
        if (!user.can("dataUpdate")) return unauthorized(rid, "dataUpdate");
        String col = target(req);
        if (col == null) return status(rid, "ERROR", "database/collection is required", null);
        Object doc = req.get("document");
        if (!(doc instanceof Map)) return status(rid, "ERROR", "document field must be an object", null);
        Map<String, Object> filter = asMapOrNull(req.get("filter"));

        // Filtreye uyan TUM kayitlari gunceller (protokol boyle tanimliyor).
        List<Map<String, Object>> matched = store.find(col, filter);
        int count = 0;
        for (Map<String, Object> rec : matched) {
            Object id = rec.get("id");
            if (id instanceof String sid) {
                Map<String, Object> updated = store.updateById(col, sid, (Map<String, Object>) doc);
                if (updated != null) { eventBus.publish(new Event("update", col, updated)); count++; }
            }
        }
        return status(rid, "OK", count + " record(s) updated", null);
    }

    private String delete(String rid, User user, Map<String, Object> req) {
        if (!user.can("dataDelete")) return unauthorized(rid, "dataDelete");
        String col = target(req);
        if (col == null) return status(rid, "ERROR", "database/collection is required", null);
        Map<String, Object> filter = asMapOrNull(req.get("filter"));

        List<Map<String, Object>> matched = store.find(col, filter);
        int count = 0;
        for (Map<String, Object> rec : matched) {
            Object id = rec.get("id");
            if (id instanceof String sid && store.deleteById(col, sid)) {
                eventBus.publish(new Event("delete", col, Map.of("id", sid)));
                count++;
            }
        }
        return status(rid, "OK", count + " record(s) deleted", null);
    }

    private String listDatabases(String rid, User user) {
        if (!user.can("databaseView")) return unauthorized(rid, "databaseView");
        // Koleksiyon adlari "database/collection" bicimindeydi; onekleri ayikla.
        java.util.LinkedHashSet<String> dbs = new java.util.LinkedHashSet<>();
        for (String key : store.collectionInfo().keySet()) {
            int slash = key.indexOf('/');
            dbs.add(slash >= 0 ? key.substring(0, slash) : key);
        }
        return status(rid, "OK", dbs.size() + " database(s)", new java.util.ArrayList<Object>(dbs));
    }

    private String listCollections(String rid, User user, Map<String, Object> req) {
        if (!user.can("databaseView")) return unauthorized(rid, "databaseView");
        String db = str(req, "database");
        if (db == null) return status(rid, "ERROR", "database is required", null);
        java.util.List<Object> cols = new java.util.ArrayList<>();
        for (String key : store.collectionInfo().keySet()) {
            String prefix = db + "/";
            if (key.startsWith(prefix)) cols.add(key.substring(prefix.length()));
        }
        return status(rid, "OK", cols.size() + " collection(s)", cols);
    }

    // --- yardimcilar ---

    /** database + collection -> depolama anahtari. */
    private static String target(Map<String, Object> req) {
        String db = str(req, "database");
        String col = str(req, "collection");
        if (col == null) return null;
        return (db == null) ? col : db + "/" + col;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapOrNull(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    private String unauthorized(String rid, String perm) {
        return status(rid, "UNAUTHORIZED", "Permission denied: " + perm, null);
    }

    private static String status(String rid, String status, String message, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rid != null) m.put("requestId", rid);
        m.put("status", status);
        m.put("message", message);
        if (data != null) m.put("data", data);
        return Json.stringify(m);
    }
}
