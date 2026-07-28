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

public class Router {

    /** Veritabani ust verisinin tutuldugu ayrilmis alan. */
    private static final String META_DB = "__meta__";
    private static final String META_DATABASES = META_DB + "/databases";

    private final DataStore store;
    private final EventBus eventBus;
    private final AuthService auth;

    public Router(DataStore store, EventBus eventBus, AuthService auth) {
        this.store = store;
        this.eventBus = eventBus;
        this.auth = auth;
    }

    // ============================================================
    //  GIRIS NOKTASI
    // ============================================================

    public String handle(String rawJson, ClientSession session) {
        String requestId = null;
        try {
            Map<String, Object> req = Json.parseObject(rawJson);
            requestId = str(req, "requestId");

            String action = str(req, "action");
            if (action == null) {
                return error(requestId, "action field is required");
            }

            // PROTOKOL.md: her istek kimlik bilgisi tasir.
            User user = auth.authenticate(str(req, "username"), str(req, "password"));
            if (user == null) {
                return unauthorized(requestId, "Invalid username or password");
            }

            return switch (action.toUpperCase()) {
                // --- PROTOKOL.md v1 cekirdek islemleri ---
                case "PING"             -> ping(requestId);
                case "READ"             -> read(requestId, user, req);
                case "WRITE"            -> write(requestId, user, req);
                case "UPDATE"           -> update(requestId, user, req);
                case "DELETE"           -> delete(requestId, user, req);
                case "LIST_DATABASES"   -> listDatabases(requestId, user);
                case "LIST_COLLECTIONS" -> listCollections(requestId, user, req);

                // --- Kimlik ---
                case "LOGIN"            -> login(requestId, user);

                // --- Veritabani yonetimi ---
                case "CREATE_DATABASE"  -> createDatabase(requestId, user, req);
                case "UPDATE_DATABASE"  -> updateDatabase(requestId, user, req);
                case "DELETE_DATABASE"  -> softDeleteDatabase(requestId, user, req);
                case "RESTORE_DATABASE" -> restoreDatabase(requestId, user, req);
                case "DROP_DATABASE"    -> dropDatabase(requestId, user, req);
                case "LIST_DATABASES_INFO" -> listDatabasesInfo(requestId, user, req);

                // --- Koleksiyon yonetimi ---
                case "CREATE_COLLECTION" -> createCollection(requestId, user, req);
                case "DROP_COLLECTION"   -> dropCollection(requestId, user, req);

                // --- Kullanici yonetimi (super admin) ---
                case "LIST_USERS"       -> listUsers(requestId, user);
                case "CREATE_USER"      -> createUser(requestId, user, req);

                // --- Ozet bilgiler ---
                case "STATS"            -> stats(requestId, user);

                // --- Observer (canli guncelleme) ---
                case "SUBSCRIBE"        -> subscribe(requestId, req, session);
                case "UNSUBSCRIBE"      -> unsubscribe(requestId, req, session);

                default -> error(requestId, "Unknown action: " + action);
            };

        } catch (Json.JsonException e) {
            return error(requestId, "Invalid JSON: " + e.getMessage());
        } catch (Denied e) {
            return unauthorized(requestId, e.getMessage());
        } catch (Invalid e) {
            return error(requestId, e.getMessage());
        } catch (Exception e) {
            return error(requestId, "Server error: " + e.getMessage());
        }
    }

    // ============================================================
    //  CEKIRDEK ISLEMLER (PROTOKOL.md v1)
    // ============================================================

    /** Ister_0018: veritabani erisilebilir mi. */
    private String ping(String rid) {
        return ok(rid, "Database is active", List.of());
    }

    /** Ister_0016: filtreye uyan kayitlari doner. */
    private String read(String rid, User user, Map<String, Object> req) {
        require(user, "dataView");
        String col = target(req);
        List<Map<String, Object>> records = store.find(col, mapOrNull(req.get("filter")));
        return ok(rid, records.size() + " record(s) found", records);
    }

    /** Ister_0017: yeni kayit ekler. */
    private String write(String rid, User user, Map<String, Object> req) {
        require(user, "dataCreate");
        String col = target(req);
        Map<String, Object> doc = mapOrNull(req.get("document"));
        if (doc == null) throw new Invalid("document field must be an object");

        Map<String, Object> inserted = store.insert(col, doc);
        eventBus.publish(new Event("insert", col, inserted));
        return ok(rid, "1 record inserted", List.of(inserted));
    }

    /** Filtreye uyan TUM kayitlari gunceller. */
    private String update(String rid, User user, Map<String, Object> req) {
        require(user, "dataUpdate");
        String col = target(req);
        Map<String, Object> doc = mapOrNull(req.get("document"));
        if (doc == null) throw new Invalid("document field must be an object");

        List<Map<String, Object>> matched = store.find(col, mapOrNull(req.get("filter")));
        List<Map<String, Object>> updated = new ArrayList<>();
        for (Map<String, Object> rec : matched) {
            Object id = rec.get("id");
            if (id instanceof String sid) {
                Map<String, Object> u = store.updateById(col, sid, doc);
                if (u != null) {
                    eventBus.publish(new Event("update", col, u));
                    updated.add(u);
                }
            }
        }
        return ok(rid, updated.size() + " record(s) updated", updated);
    }

    /** Filtreye uyan TUM kayitlari siler. */
    private String delete(String rid, User user, Map<String, Object> req) {
        require(user, "dataDelete");
        String col = target(req);
        List<Map<String, Object>> matched = store.find(col, mapOrNull(req.get("filter")));
        int count = 0;
        for (Map<String, Object> rec : matched) {
            Object id = rec.get("id");
            if (id instanceof String sid && store.deleteById(col, sid)) {
                eventBus.publish(new Event("delete", col, Map.of("id", sid)));
                count++;
            }
        }
        return ok(rid, count + " record(s) deleted", List.of());
    }

    /**
     * Veritabani ADLARINI doner (duz string listesi).
     * PROTOKOL.md v1 sozlesmesi geregi bicimi degistirilmemistir;
     * zengin bilgi icin LIST_DATABASES_INFO kullanilir.
     */
    private String listDatabases(String rid, User user) {
        require(user, "databaseView");
        List<Object> names = new ArrayList<>(activeDatabaseNames());
        return ok(rid, names.size() + " database(s)", names);
    }

    /** Koleksiyon ADLARINI doner (duz string listesi). */
    private String listCollections(String rid, User user, Map<String, Object> req) {
        require(user, "databaseView");
        String db = str(req, "database");
        if (db == null) throw new Invalid("database field is required");
        List<Object> names = new ArrayList<>(store.listCollections(db));
        return ok(rid, names.size() + " collection(s)", names);
    }

    // ============================================================
    //  KIMLIK
    // ============================================================

    /**
     * Ister_0002: girisin dogrulugunu kontrol eder.
     * Kimlik zaten handle() icinde dogrulanmistir; burada kullanici
     * bilgileri (rol, yetkiler, departmanlar) dondurulur.
     */
    private String login(String rid, User user) {
        return ok(rid, "Login successful", List.of(user.toPublicMap()));
    }

    // ============================================================
    //  VERITABANI YONETIMI
    // ============================================================

    private String createDatabase(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String name = databaseName(req);

        if (findDatabaseMeta(name) != null) {
            throw new Invalid("Database already exists: " + name);
        }

        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("department", strOr(doc, "department", ""));
        meta.put("description", strOr(doc, "description", ""));
        meta.put("isDeleted", false);
        meta.put("deletedBy", null);

        Map<String, Object> created = store.insert(META_DATABASES, meta);
        eventBus.publish(new Event("insert", META_DATABASES, created));
        return ok(rid, "Database created: " + name, List.of(enrich(created)));
    }

    private String updateDatabase(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String name = databaseName(req);
        Map<String, Object> meta = findDatabaseMeta(name);
        if (meta == null) throw new Invalid("Database not found: " + name);

        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        Map<String, Object> patch = new LinkedHashMap<>();
        if (doc.containsKey("department"))  patch.put("department", doc.get("department"));
        if (doc.containsKey("description")) patch.put("description", doc.get("description"));

        Map<String, Object> updated = store.updateById(
                META_DATABASES, (String) meta.get("id"), patch);
        eventBus.publish(new Event("update", META_DATABASES, updated));
        return ok(rid, "Database updated: " + name, List.of(enrich(updated)));
    }

    /** Yumusak silme: veriler durur, listelerde gorunmez, geri alinabilir. */
    private String softDeleteDatabase(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String name = databaseName(req);
        Map<String, Object> meta = findDatabaseMeta(name);
        if (meta == null) throw new Invalid("Database not found: " + name);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isDeleted", true);
        patch.put("deletedBy", user.email());
        Map<String, Object> updated = store.updateById(
                META_DATABASES, (String) meta.get("id"), patch);
        eventBus.publish(new Event("update", META_DATABASES, updated));
        return ok(rid, "Database moved to trash: " + name, List.of(enrich(updated)));
    }

    private String restoreDatabase(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String name = databaseName(req);
        Map<String, Object> meta = findDatabaseMeta(name);
        if (meta == null) throw new Invalid("Database not found: " + name);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("isDeleted", false);
        patch.put("deletedBy", null);
        Map<String, Object> updated = store.updateById(
                META_DATABASES, (String) meta.get("id"), patch);
        eventBus.publish(new Event("update", META_DATABASES, updated));
        return ok(rid, "Database restored: " + name, List.of(enrich(updated)));
    }

    /** Kalici silme: ust veri ve TUM koleksiyonlar gider. */
    private String dropDatabase(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String name = databaseName(req);

        int removed = store.dropDatabase(name);
        Map<String, Object> meta = findDatabaseMeta(name);
        if (meta != null) {
            store.deleteById(META_DATABASES, (String) meta.get("id"));
        }
        if (meta == null && removed == 0) {
            throw new Invalid("Database not found: " + name);
        }
        eventBus.publish(new Event("delete", META_DATABASES, Map.of("name", name)));
        return ok(rid, "Database dropped: " + name + " (" + removed + " collection(s))", List.of());
    }

    /**
     * Veritabanlarini ust verileriyle birlikte doner (ad, departman, aciklama,
     * koleksiyon sayisi, kayit sayisi, silinme durumu).
     * filter.includeDeleted = true ise silinenler de gelir.
     */
    private String listDatabasesInfo(String rid, User user, Map<String, Object> req) {
        require(user, "databaseView");
        Map<String, Object> filter = mapOrEmpty(req.get("filter"));
        boolean includeDeleted = Boolean.TRUE.equals(filter.get("includeDeleted"));

        List<Map<String, Object>> metas = store.find(META_DATABASES, null);
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();

        for (Map<String, Object> meta : metas) {
            boolean deleted = Boolean.TRUE.equals(meta.get("isDeleted"));
            if (deleted && !includeDeleted) continue;
            result.add(enrich(meta));
            seen.add(String.valueOf(meta.get("name")));
        }

        // Ust verisi olmayan ama veri tasiyan veritabanlari da gorunsun
        // (orn. Arka Yuz dogrudan WRITE ile olusturmus olabilir).
        for (String name : store.listDatabases()) {
            if (META_DB.equals(name) || seen.contains(name)) continue;
            Map<String, Object> implicit = new LinkedHashMap<>();
            implicit.put("id", name);
            implicit.put("name", name);
            implicit.put("department", "");
            implicit.put("description", "");
            implicit.put("isDeleted", false);
            implicit.put("deletedBy", null);
            result.add(enrich(implicit));
        }

        return ok(rid, result.size() + " database(s)", result);
    }

    // ============================================================
    //  KOLEKSIYON YONETIMI
    // ============================================================

    private String createCollection(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        boolean created = store.createCollection(db, col);
        if (!created) throw new Invalid("Collection already exists: " + col);

        eventBus.publish(new Event("insert", DataStore.key(db, col), Map.of("collection", col)));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("database", db);
        info.put("collection", col);
        info.put("recordCount", 0L);
        return ok(rid, "Collection created: " + col, List.of(info));
    }

    private String dropCollection(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        boolean dropped = store.dropCollection(db, col);
        if (!dropped) throw new Invalid("Collection not found: " + col);

        eventBus.publish(new Event("delete", DataStore.key(db, col), Map.of("collection", col)));
        return ok(rid, "Collection dropped: " + col, List.of());
    }

    // ============================================================
    //  KULLANICI YONETIMI (Ister_0004, Ister_0005)
    // ============================================================

    private String listUsers(String rid, User admin) {
        requireSuperAdmin(admin);
        List<Map<String, Object>> users = new ArrayList<>();
        for (User u : auth.allUsers()) users.add(u.toPublicMap());
        return ok(rid, users.size() + " user(s)", users);
    }

    private String createUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        Map<String, Object> doc = mapOrEmpty(req.get("document"));

        String email = str(doc, "email");
        if (email == null) throw new Invalid("document.email is required");

        User created = new User(
                "user-" + System.currentTimeMillis(),
                strOr(doc, "name", ""),
                email,
                strOr(doc, "password", "changeme"),
                strOr(doc, "role", "user"),
                stringSet(doc.get("departments")),
                stringSet(doc.get("permissions")));

        if (!auth.createUser(created)) throw new Invalid("Email already exists: " + email);
        return ok(rid, "User created: " + email, List.of(created.toPublicMap()));
    }

    // ============================================================
    //  OZET BILGILER
    // ============================================================

    private String stats(String rid, User user) {
        List<String> databases = activeDatabaseNames();
        long totalRecords = 0;
        long totalCollections = 0;
        for (Map.Entry<String, Object> e : store.collectionInfo().entrySet()) {
            if (e.getKey().startsWith(META_DB + "/")) continue;
            totalCollections++;
            totalRecords += ((Number) e.getValue()).longValue();
        }

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalDatabases", (long) databases.size());
        s.put("totalCollections", totalCollections);
        s.put("totalRecords", totalRecords);
        s.put("activeUsers", (long) auth.activeUserCount());
        return ok(rid, "Statistics", List.of(s));
    }

    // ============================================================
    //  OBSERVER (canli guncelleme)
    // ============================================================

    private String subscribe(String rid, Map<String, Object> req, ClientSession session) {
        String col = target(req);
        if (session != null) session.subscribe(col);
        return ok(rid, "Subscribed: " + col, List.of());
    }

    private String unsubscribe(String rid, Map<String, Object> req, ClientSession session) {
        String col = target(req);
        if (session != null) session.unsubscribe(col);
        return ok(rid, "Unsubscribed: " + col, List.of());
    }

    // ============================================================
    //  YARDIMCILAR
    // ============================================================

    /** database + collection -> depolama anahtari. */
    private static String target(Map<String, Object> req) {
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");
        return DataStore.key(str(req, "database"), col);
    }

    private static String databaseName(Map<String, Object> req) {
        String db = str(req, "database");
        if (db == null) throw new Invalid("database field is required");
        if (META_DB.equals(db)) throw new Invalid("Reserved database name: " + META_DB);
        return db;
    }

    /** Silinmemis veritabani adlari (ust veri + fiilen var olanlar). */
    private List<String> activeDatabaseNames() {
        java.util.Set<String> deleted = new java.util.HashSet<>();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();

        for (Map<String, Object> meta : store.find(META_DATABASES, null)) {
            String name = String.valueOf(meta.get("name"));
            if (Boolean.TRUE.equals(meta.get("isDeleted"))) deleted.add(name);
            else names.add(name);
        }
        for (String name : store.listDatabases()) {
            if (!META_DB.equals(name) && !deleted.contains(name)) names.add(name);
        }

        List<String> result = new ArrayList<>(names);
        java.util.Collections.sort(result);
        return result;
    }

    private Map<String, Object> findDatabaseMeta(String name) {
        List<Map<String, Object>> found = store.find(META_DATABASES, Map.of("name", name));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Ust veriye koleksiyon ve kayit sayilarini ekler. */
    private Map<String, Object> enrich(Map<String, Object> meta) {
        String name = String.valueOf(meta.get("name"));
        List<String> cols = store.listCollections(name);
        long records = 0;
        Map<String, Object> info = store.collectionInfo();
        for (String c : cols) {
            Object n = info.get(DataStore.key(name, c));
            if (n instanceof Number num) records += num.longValue();
        }
        Map<String, Object> out = new LinkedHashMap<>(meta);
        out.put("collections", cols);
        out.put("collectionCount", (long) cols.size());
        out.put("recordCount", records);
        return out;
    }

    private void require(User user, String permission) {
        if (!user.can(permission)) throw new Denied("Permission denied: " + permission);
    }

    private void requireSuperAdmin(User user) {
        if (!user.isSuperAdmin()) throw new Denied("Super admin required");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrNull(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private static java.util.Set<String> stringSet(Object o) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        if (o instanceof List<?> list) {
            for (Object x : list) if (x != null) set.add(x.toString());
        }
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

    // ============================================================
    //  CEVAP ZARFI
    // ============================================================

    private static String ok(String rid, String message, List<?> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rid != null) m.put("requestId", rid);
        m.put("status", "OK");
        m.put("message", message);
        m.put("data", data);
        return Json.stringify(m);
    }

    private static String unauthorized(String rid, String message) {
        return envelope(rid, "UNAUTHORIZED", message);
    }

    private static String error(String rid, String message) {
        return envelope(rid, "ERROR", message);
    }

    private static String envelope(String rid, String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rid != null) m.put("requestId", rid);
        m.put("status", status);
        m.put("message", message);
        m.put("data", List.of());
        return Json.stringify(m);
    }

    /** Yetki reddi -> UNAUTHORIZED. */
    private static class Denied extends RuntimeException {
        Denied(String m) { super(m); }
    }

    /** Gecersiz istek -> ERROR. */
    private static class Invalid extends RuntimeException {
        Invalid(String m) { super(m); }
    }
}
