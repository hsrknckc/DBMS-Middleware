package middleware.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jsonparser.Json;
import middleware.auth.AuthService;
import middleware.auth.User;
import middleware.events.Event;
import middleware.events.EventBus;
import middleware.server.ClientSession;
import middleware.storage.DataStore;

/**
 * Istekleri action'a gore yonlendiren katman.
 *
 * PROTOKOL ZARFI (frontend'in tcp_socket_service.dart formati):
 *   Istek : {"requestId":"..","action":"databases.list","token":"..","payload":{..}}
 *   Cevap : {"requestId":"..","ok":true,"data":..}
 *           {"requestId":"..","ok":false,"error":".."}
 *
 * Action isimleri "alan.islem" bicimindedir (auth.login, databases.create...).
 * requestId cevaba AYNEN geri konur; frontend istek/cevap eslesmesini bununla yapar.
 *
 * Yetki (Ister_0013, 0015): yazma/silme/guncelleme action'lari once token ile
 * kullaniciyi cozer, sonra ilgili Permission'i kontrol eder. Yetkisiz istek
 * {"ok":false,"error":"..."} ile reddedilir.
 */
public class RequestRouter {

    private final DataStore store;
    private final EventBus eventBus;
    private final AuthService auth;

    public RequestRouter(DataStore store, EventBus eventBus, AuthService auth) {
        this.store = store;
        this.eventBus = eventBus;
        this.auth = auth;
    }

    public String handle(String rawJson, ClientSession session) {
        String requestId = null;
        try {
            Map<String, Object> request = Json.parseObject(rawJson);
            requestId = str(request, "requestId");

            Object actionObj = request.get("action");
            if (!(actionObj instanceof String action) || action.isBlank()) {
                return error(requestId, "Request is missing a valid 'action' field");
            }

            Map<String, Object> payload = asMap(request.get("payload"));
            String token = str(request, "token");
            User user = auth.resolve(token); // null olabilir

            return switch (action) {
                // --- auth ---
                case "auth.login"        -> authLogin(requestId, payload);
                case "auth.logout"       -> authLogout(requestId, token);
                case "auth.me"           -> authMe(requestId, user);
                case "auth.requestReset" -> okData(requestId, Map.of("sent", true));

                // --- databases ---
                case "databases.list"    -> dbList(requestId, user, payload);
                case "databases.getById" -> dbGetById(requestId, user, payload);
                case "databases.create"  -> dbCreate(requestId, user, payload);
                case "databases.update"  -> dbUpdate(requestId, user, payload);
                case "databases.softDelete"      -> dbSoftDelete(requestId, user, payload);
                case "databases.restore"         -> dbRestore(requestId, user, payload);
                case "databases.permanentDelete" -> dbPermanentDelete(requestId, user, payload);

                // --- records (Data Explorer) ---
                case "records.list"    -> recList(requestId, user, payload);
                case "records.getById" -> recGetById(requestId, user, payload);
                case "records.create"  -> recCreate(requestId, user, payload);
                case "records.update"  -> recUpdate(requestId, user, payload);
                case "records.delete"  -> recDelete(requestId, user, payload);
                case "records.import"  -> recImport(requestId, user, payload);
                case "records.export"  -> recExport(requestId, user, payload);

                // --- users ---
                case "users.list"    -> userList(requestId, user, payload);
                case "users.getById" -> userGetById(requestId, user, payload);
                case "users.create"  -> userCreate(requestId, user, payload);

                // --- dashboard ---
                case "dashboard.stats"        -> dashStats(requestId, user);
                case "dashboard.systemStatus" -> okData(requestId, Map.of("online", true));

                // --- server aktiflik (Ister_0018) ---
                case "ping" -> okData(requestId, Map.of("message", "pong"));

                default -> error(requestId, "Unknown action: " + action);
            };

        } catch (Json.JsonException e) {
            return error(requestId, "Invalid JSON: " + e.getMessage());
        } catch (AuthError e) {
            return error(requestId, e.getMessage());
        } catch (Exception e) {
            return error(requestId, "Server error: " + e.getMessage());
        }
    }

    // ==================== AUTH ====================

    private String authLogin(String rid, Map<String, Object> payload) {
        String email = str(payload, "email");
        String password = str(payload, "password");
        if (email == null || password == null) {
            return error(rid, "'email' and 'password' are required");
        }
        String token = auth.login(email, password);
        if (token == null) {
            return error(rid, "Invalid email or password");
        }
        User u = auth.byEmail(email);
        Map<String, Object> data = new LinkedHashMap<>(u.toPublicMap());
        data.put("token", token);
        return okData(rid, data);
    }

    private String authLogout(String rid, String token) {
        auth.logout(token);
        return okData(rid, Map.of("loggedOut", true));
    }

    private String authMe(String rid, User user) {
        if (user == null) return error(rid, "Not authenticated");
        return okData(rid, user.toPublicMap());
    }

    // ==================== DATABASES ====================
    // Bir database sistemde "__databases__" adli ozel koleksiyonda tutulur.

    private static final String DB_META = "__databases__";

    private String dbList(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseView");
        boolean includeDeleted = bool(payload, "includeDeleted");
        Map<String, Object> filter = includeDeleted ? null : Map.of("isDeleted", false);
        List<Map<String, Object>> list = store.find(DB_META, filter);
        return okData(rid, list);
    }

    private String dbGetById(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseView");
        Map<String, Object> db = one(store.find(DB_META, Map.of("id", reqId(payload))));
        if (db == null) return error(rid, "Database not found");
        return okData(rid, db);
    }

    private String dbCreate(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseCreate");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", str(payload, "name"));
        data.put("department", str(payload, "department"));
        data.put("description", strOr(payload, "description", ""));
        data.put("isDeleted", false);
        Map<String, Object> created = store.insert(DB_META, data);
        eventBus.publish(new Event("insert", DB_META, created));
        return okData(rid, created);
    }

    private String dbUpdate(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseCreate");
        String id = reqId(payload);
        Map<String, Object> updated = store.updateById(DB_META, id, payload);
        if (updated == null) return error(rid, "Database not found");
        eventBus.publish(new Event("update", DB_META, updated));
        return okData(rid, updated);
    }

    private String dbSoftDelete(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseCreate");
        String id = reqId(payload);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isDeleted", true);
        patch.put("deletedBy", user.id());
        Map<String, Object> updated = store.updateById(DB_META, id, patch);
        if (updated == null) return error(rid, "Database not found");
        eventBus.publish(new Event("update", DB_META, updated));
        return okData(rid, updated);
    }

    private String dbRestore(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseCreate");
        String id = reqId(payload);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isDeleted", false);
        patch.put("deletedBy", null);
        Map<String, Object> updated = store.updateById(DB_META, id, patch);
        if (updated == null) return error(rid, "Database not found");
        eventBus.publish(new Event("update", DB_META, updated));
        return okData(rid, updated);
    }

    private String dbPermanentDelete(String rid, User user, Map<String, Object> payload) {
        require(user, "databaseCreate");
        String id = reqId(payload);
        boolean deleted = store.deleteById(DB_META, id);
        if (!deleted) return error(rid, "Database not found");
        eventBus.publish(new Event("delete", DB_META, Map.of("id", id)));
        return okData(rid, Map.of("deleted", true));
    }

    // ==================== RECORDS ====================

    private String recList(String rid, User user, Map<String, Object> payload) {
        require(user, "dataView");
        String col = recCollection(payload);
        Map<String, Object> filter = null;
        String q = str(payload, "searchQuery");
        List<Map<String, Object>> list = store.find(col, filter);
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase();
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> r : list) {
                if (Json.stringify(r).toLowerCase().contains(needle)) filtered.add(r);
            }
            list = filtered;
        }
        return okData(rid, list);
    }

    private String recGetById(String rid, User user, Map<String, Object> payload) {
        require(user, "dataView");
        // id global benzersiz oldugu icin tum kayitlarda aranir degil; koleksiyon verilir
        String col = recCollection(payload);
        Map<String, Object> rec = one(store.find(col, Map.of("id", reqId(payload))));
        if (rec == null) return error(rid, "Record not found");
        return okData(rid, rec);
    }

    @SuppressWarnings("unchecked")
    private String recCreate(String rid, User user, Map<String, Object> payload) {
        require(user, "dataCreate");
        String col = recCollection(payload);
        Map<String, Object> data = asMap(payload.get("data"));
        Map<String, Object> created = store.insert(col, data);
        eventBus.publish(new Event("insert", col, created));
        return okData(rid, created);
    }

    @SuppressWarnings("unchecked")
    private String recUpdate(String rid, User user, Map<String, Object> payload) {
        require(user, "dataUpdate");
        String col = recCollection(payload);
        String id = reqId(payload);
        // payload ya duz kaydi ya da {data:{...}} tasiyabilir
        Map<String, Object> data = payload.containsKey("data")
                ? asMap(payload.get("data")) : payload;
        Map<String, Object> updated = store.updateById(col, id, data);
        if (updated == null) return error(rid, "Record not found: " + id);
        eventBus.publish(new Event("update", col, updated));
        return okData(rid, updated);
    }

    private String recDelete(String rid, User user, Map<String, Object> payload) {
        require(user, "dataDelete");
        String col = recCollection(payload);
        String id = reqId(payload);
        boolean deleted = store.deleteById(col, id);
        if (!deleted) return error(rid, "Record not found: " + id);
        eventBus.publish(new Event("delete", col, Map.of("id", id)));
        return okData(rid, Map.of("deleted", true));
    }

    @SuppressWarnings("unchecked")
    private String recImport(String rid, User user, Map<String, Object> payload) {
        require(user, "dataImport");
        String col = recCollection(payload);
        Object recordsObj = payload.get("records");
        if (recordsObj == null) recordsObj = payload.get("data");
        if (!(recordsObj instanceof List<?> list) || list.isEmpty()) {
            return error(rid, "'records' must be a non-empty JSON array");
        }
        for (Object o : list) if (!(o instanceof Map)) return error(rid, "each record must be a JSON object");
        List<String> ids = store.insertMany(col, (List<Map<String, Object>>) (List<?>) list);
        eventBus.publish(new Event("insert_many", col, Map.of("count", (long) ids.size())));
        return okData(rid, Map.of("count", (long) ids.size(), "ids", ids));
    }

    private String recExport(String rid, User user, Map<String, Object> payload) {
        require(user, "dataExport");
        String col = recCollection(payload);
        List<Map<String, Object>> list = store.find(col, null);
        String format = strOr(payload, "format", "json");
        String out = "csv".equalsIgnoreCase(format) ? toCsv(list) : Json.stringify(list);
        return okData(rid, out);
    }

    // ==================== USERS ====================

    private String userList(String rid, User admin, Map<String, Object> payload) {
        requireSuperAdmin(admin);
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : auth.allUsers()) list.add(u.toPublicMap());
        return okData(rid, list);
    }

    private String userGetById(String rid, User admin, Map<String, Object> payload) {
        requireSuperAdmin(admin);
        User u = auth.byId(reqId(payload));
        if (u == null) return error(rid, "User not found");
        return okData(rid, u.toPublicMap());
    }

    @SuppressWarnings("unchecked")
    private String userCreate(String rid, User admin, Map<String, Object> payload) {
        requireSuperAdmin(admin); // Ister_0005: yeni kullaniciyi super admin ekler
        String email = str(payload, "email");
        if (email == null) return error(rid, "'email' is required");
        java.util.Set<String> depts = toStringSet(payload.get("departments"));
        java.util.Set<String> perms = toStringSet(payload.get("permissions"));
        User u = new User(
                "user-" + System.currentTimeMillis(),
                strOr(payload, "name", ""), email,
                strOr(payload, "password", "changeme"),
                strOr(payload, "role", "user"),
                depts, perms);
        if (!auth.createUser(u)) return error(rid, "Email already exists");
        return okData(rid, u.toPublicMap());
    }

    // ==================== DASHBOARD ====================

    private String dashStats(String rid, User user) {
        if (user == null) throw new AuthError("Not authenticated");
        List<Map<String, Object>> dbs = store.find(DB_META, Map.of("isDeleted", false));
        Map<String, Object> colInfo = store.collectionInfo();
        long totalRecords = 0;
        int collectionCount = 0;
        for (Map.Entry<String, Object> e : colInfo.entrySet()) {
            if (e.getKey().equals(DB_META)) continue;
            collectionCount++;
            totalRecords += ((Number) e.getValue()).longValue();
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDatabases", (long) dbs.size());
        stats.put("totalCollections", (long) collectionCount);
        stats.put("totalRecords", totalRecords);
        stats.put("activeUsers", (long) auth.activeUserCount());
        return okData(rid, stats);
    }

    // ==================== YARDIMCILAR ====================

    /** records icin hedef koleksiyon: databaseId/collectionName. */
    private static String recCollection(Map<String, Object> payload) {
        String db = str(payload, "databaseId");
        String col = str(payload, "collectionName");
        if (col == null) throw new AuthError("'collectionName' is required");
        return (db == null) ? col : db + "/" + col;
    }

    private static String reqId(Map<String, Object> payload) {
        String id = str(payload, "id");
        if (id == null) id = str(payload, "userId");
        if (id == null) throw new AuthError("'id' is required");
        return id;
    }

    private void require(User user, String permission) {
        if (user == null) throw new AuthError("Not authenticated");
        if (!user.can(permission)) throw new AuthError("Permission denied: " + permission);
    }

    private void requireSuperAdmin(User user) {
        if (user == null) throw new AuthError("Not authenticated");
        if (!user.isSuperAdmin()) throw new AuthError("Super admin required");
    }

    private static Map<String, Object> one(List<Map<String, Object>> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static java.util.Set<String> toStringSet(Object o) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (o instanceof List<?> list) for (Object x : list) if (x != null) set.add(x.toString());
        return set;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    private static String strOr(Map<String, Object> m, String key, String def) {
        String v = str(m, key);
        return v == null ? def : v;
    }

    private static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    private static String toCsv(List<Map<String, Object>> list) {
        if (list.isEmpty()) return "";
        java.util.LinkedHashSet<String> cols = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : list) cols.addAll(r.keySet());
        StringBuilder sb = new StringBuilder(String.join(",", cols)).append("\n");
        for (Map<String, Object> r : list) {
            List<String> row = new ArrayList<>();
            for (String c : cols) row.add(String.valueOf(r.getOrDefault(c, "")));
            sb.append(String.join(",", row)).append("\n");
        }
        return sb.toString();
    }

    // ==================== CEVAP ZARFI ====================

    private static String okData(String requestId, Object data) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (requestId != null) m.put("requestId", requestId);
        m.put("ok", true);
        m.put("data", data);
        return Json.stringify(m);
    }

    private static String error(String requestId, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (requestId != null) m.put("requestId", requestId);
        m.put("ok", false);
        m.put("error", message);
        return Json.stringify(m);
    }

    /** Yetki/dogrulama hatalarini tek noktada yakalamak icin. */
    private static class AuthError extends RuntimeException {
        AuthError(String m) { super(m); }
    }
}
