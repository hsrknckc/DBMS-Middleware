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
import middleware.audit.AuditService;
import middleware.file.RequestFileService;
import middleware.validation.SchemaValidator;
import middleware.validation.FilterSanitizer;
import middleware.server.ClientSession;
import middleware.storage.Store;

public class Router {

    /** Veritabani ust verisinin tutuldugu ayrilmis alan. */
    private static final String META_DB = "__meta__";
    private static final String META_DATABASES = META_DB + "/databases";

    /** Sema (alan tanimi) ust verisinin tutuldugu ayrilmis koleksiyon. */
    private static final String META_SCHEMAS = META_DB + "/schemas";

    /** Tek bir UPDATE isteginde guncellenebilecek maksimum kayit sayisi. */
    private static final int MAX_UPDATE_BATCH = 500;

    /** DEFINE_FIELDS ile kabul edilen tip adlari. */
    private static final java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of(
            "string", "text", "int", "integer", "long",
            "double", "float", "number", "decimal",
            "boolean", "bool", "array", "list", "object", "map", "any");

    private final Store store;
    private final EventBus eventBus;
    private final AuthService auth;
    private final RequestFileService files;
    private final AuditService audit;

    /**
     * Tanimlanmamis alanlarin tipi ilk yazmada ogrenilsin mi?
     * Acikken frontend disindan gelen yazmalar da korunur.
     */
    private final boolean autoSchema;

    public Router(Store store, EventBus eventBus, AuthService auth,
                  RequestFileService files, AuditService audit) {
        this(store, eventBus, auth, files, audit, true);
    }

    public Router(Store store, EventBus eventBus, AuthService auth,
                  RequestFileService files, AuditService audit, boolean autoSchema) {
        this.store = store;
        this.eventBus = eventBus;
        this.auth = auth;
        this.files = files;
        this.audit = audit;
        this.autoSchema = autoSchema;
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

            // Istek istege bagli olarak "name" (kullanicinin gorunen adi) tasir.
            // Bu alan YALNIZCA kayit/gunlukleme icindir; yetkilendirmede
            // kullanilmaz. Guvenilir ad her zaman kimlik dogrulamasindan
            // gelen addir — aksi halde istemci baskasinin adini yazabilirdi.
            String claimedName = str(req, "name");
            if (claimedName != null && !claimedName.equals(user.name())) {
                System.out.println("[auth] UYARI: istekteki ad ('" + claimedName
                        + "') kayitli addan farkli ('" + user.name() + "') - kayitli ad kullanildi");
            }

            logAction(user, action, req);

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
                case "DROP_COLLECTION", "DELETE_COLLECTION" -> dropCollection(requestId, user, req);

                // --- Kullanici yonetimi (Ister_0004, Ister_0005) ---
                case "LIST_USERS"              -> listUsers(requestId, user, req);
                case "CREATE_USER"             -> createUser(requestId, user, req);
                case "UPDATE_USER"             -> updateUser(requestId, user, req);
                case "UPDATE_USER_PERMISSIONS" -> updateUserPermissions(requestId, user, req);
                case "DELETE_USER"             -> deleteUser(requestId, user, req);
                case "RESTORE_USER"            -> restoreUser(requestId, user, req);
                case "DROP_USER"               -> dropUser(requestId, user, req);
                case "RESET_USER_PASSWORD"     -> resetUserPassword(requestId, user, req);

                // --- Denetim kayitlari ---
                case "AUDIT_LOGS"        -> auditLogs(requestId, user, req);
                case "RECENT_ACTIVITIES" -> recentActivities(requestId, user, req);
                case "REVERT_AUDIT_LOG"  -> revertAuditLog(requestId, user, req);

                // --- Talep dosyasi (Ister_0011, Ister_0012) ---
                case "CHECK_FILE"          -> checkFile(requestId, user, req);
                case "IMPORT_FILE"         -> importFile(requestId, user, req);
                case "DESCRIBE_COLLECTION" -> describeCollection(requestId, user, req);

                // --- Alan (feature) tanimlari ---
                case "DEFINE_FIELDS", "DEFINE_FIELD" -> defineFields(requestId, user, req);
                case "DELETE_FIELD"                  -> deleteField(requestId, user, req);

                // --- Ozet bilgiler ---
                case "STATS"         -> stats(requestId, user);
                case "SYSTEM_STATUS" -> systemStatus(requestId);

                // --- Observer (canli guncelleme) ---
                case "SUBSCRIBE"        -> subscribe(requestId, req, session);
                case "UNSUBSCRIBE"      -> unsubscribe(requestId, req, session);

                default -> error(requestId, "Unknown action: " + action);
            };

        } catch (Json.JsonException e) {
            return error(requestId, "Invalid JSON: " + e.getMessage());
        } catch (Denied e) {
            return unauthorized(requestId, e.getMessage());
        } catch (RequestFileService.PathNotAllowed e) {
            return error(requestId, e.getMessage());
        } catch (RequestFileService.FileProblem e) {
            return error(requestId, e.getMessage());
        } catch (Invalid e) {
            return error(requestId, e.getMessage());
        } catch (Exception e) {
            return error(requestId, "Server error: " + e.getMessage());
        }
    }

    /**
     * Islemi kim yapti, neyi hedefledi — okunabilir tek satir.
     *
     * Ham JSON loglari kullanici adini e-posta olarak gosteriyordu; bu satir
     * kisinin adini da yazarak loglarin takip edilebilirligini artirir.
     * Surekli tekrarlanan saglik kontrolleri (PING) haric tutulur.
     */
    private void logAction(User user, String action, Map<String, Object> req) {
        if ("PING".equalsIgnoreCase(action)) return;

        String target = "";
        String db = str(req, "database");
        String col = str(req, "collection");
        if (db != null && col != null)      target = " -> " + db + "/" + col;
        else if (db != null)                target = " -> " + db;

        System.out.println("[user] " + user.name() + " (" + user.email() + ") : "
                + action.toUpperCase() + target);
    }

    // ============================================================
    //  CEKIRDEK ISLEMLER (PROTOKOL.md v1)
    // ============================================================

    /** Ister_0018: veritabani erisilebilir mi. */
    private String ping(String rid) {
        // Ister_0018: veritabani sunucusunun aktifligini kontrol eder.
        // Bellek deposunda her zaman true; MongoDB'de gercek ping atilir.
        if (store.isHealthy()) {
            return ok(rid, "Database is active", List.of());
        }
        return error(rid, "Database is not reachable");
    }

    // /** Ister_0016: filtreye uyan kayitlari doner. */
    // private String read(String rid, User user, Map<String, Object> req) {
    //     require(user, "dataView");
    //     String col = target(req);
    //     List<Map<String, Object>> records = store.find(col, normalizeFilter(req.get("filter")));
    //     return ok(rid, records.size() + " record(s) found", records);
    // }

    /** Ister_0016: filtreye uyan kayitlari doner. */
    private String read(String rid, User user, Map<String, Object> req) {
        require(user, "dataView");
        String col = target(req);
        Map<String, Object> filter = normalizeFilter(req.get("filter"));

        // GUVENLIK KONTROLU: Zararli operatorleri filtrele
        try {
            FilterSanitizer.validate(filter);
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }

        List<Map<String, Object>> records = store.find(col, filter);
        return ok(rid, records.size() + " record(s) found", records);
    }

    /** Ister_0017: yeni kayit ekler. */
    private String write(String rid, User user, Map<String, Object> req) {
        require(user, "dataCreate");
        String col = target(req);
        Map<String, Object> doc = mapOrNull(req.get("document"));
        if (doc == null) throw new Invalid("document field must be an object");

        validateAgainstSchema(str(req, "database"), str(req, "collection"), doc);
        learnFieldTypes(str(req, "database"), str(req, "collection"), doc);

        Map<String, Object> inserted = store.insert(col, doc);
        eventBus.publish(new Event("insert", col, inserted));
        return ok(rid, "1 record inserted", List.of(inserted));
    }

    /** Filtreye uyan kayitlari guvenle gunceller; limit asiminda hicbir kayda dokunmaz. */
    private String update(String rid, User user, Map<String, Object> req) {
        require(user, "dataUpdate");
        String col = target(req);

        // 1. Dokuman Dogrulamasi
        Map<String, Object> doc = mapOrNull(req.get("document"));
        if (doc == null || doc.isEmpty()) {
            throw new Invalid("document field must be a non-empty object");
        }

        // 2. SISTEM ALANLARI KORUMASI (Immutable Fields)
        if (doc.containsKey("id") || doc.containsKey("_id")) {
            throw new Invalid("Field 'id' cannot be updated");
        }
        if (doc.containsKey("createdAt")) {
            throw new Invalid("Field 'createdAt' cannot be updated");
        }

        // 3. Bos Filtre Kalkani
        Map<String, Object> filter = normalizeFilter(req.get("filter"));
        if (filter == null || filter.isEmpty()) {
            throw new Invalid("Filter is required for UPDATE. Mass update with empty filter is not allowed.");
        }

        // 4. Sema Uyumluluk Kontrolu
        validateAgainstSchema(str(req, "database"), str(req, "collection"), doc);

        // 5. Eslestir ve Limit Kontrolu Yap (Fail-Fast)
        List<Map<String, Object>> matched = store.find(col, filter);
        if (matched.isEmpty()) {
            return ok(rid, "0 record(s) updated", List.of());
        }

        if (matched.size() > MAX_UPDATE_BATCH) {
            throw new Invalid("Update affects too many records (" + matched.size() 
                    + "). Maximum allowed: " + MAX_UPDATE_BATCH);
        }

        // 6. Guncellemeleri Calistir (Hata durumunda geri alma korumasiyla)
        List<Map<String, Object>> updated = new ArrayList<>();
        List<Map<String, Object>> rollbackList = new ArrayList<>();

        try {
            for (Map<String, Object> oldRec : matched) {
                Object id = oldRec.get("id");
                if (id instanceof String sid) {
                    rollbackList.add(new LinkedHashMap<>(oldRec));

                    Map<String, Object> u = store.updateById(col, sid, doc);
                    if (u != null) {
                        eventBus.publish(new Event("update", col, u));
                        updated.add(u);
                    }
                }
            }
        } catch (Exception e) {
            for (Map<String, Object> oldRec : rollbackList) {
                String sid = (String) oldRec.get("id");
                store.updateById(col, sid, oldRec);
            }
            throw new Invalid("Update failed during execution, rolled back changes: " + e.getMessage());
        }

        return ok(rid, updated.size() + " record(s) updated", updated);
    }

    /** Filtreye uyan TUM kayitlari siler. */
    // private String delete(String rid, User user, Map<String, Object> req) {
    //     require(user, "dataDelete");
    //     String col = target(req);
    //     List<Map<String, Object>> matched = store.find(col, normalizeFilter(req.get("filter")));
    //     int count = 0;
    //     for (Map<String, Object> rec : matched) {
    //         Object id = rec.get("id");
    //         if (id instanceof String sid && store.deleteById(col, sid)) {
    //             eventBus.publish(new Event("delete", col, Map.of("id", sid)));
    //             count++;
    //         }
    //     }
    //     // Silinen sayi data icinde de dondurulur; istemci mesaj metnini
    //     // ayristirmadan sonucu dogrulayabilsin diye.
    //     return ok(rid, count + " record(s) deleted",
    //               List.of(Map.of("deletedCount", (long) count)));
    // }

    /** Filtreye uyan kayitlari siler (Bos/null filtre ile toplu silme engellenmistir). */
    private String delete(String rid, User user, Map<String, Object> req) {
        require(user, "dataDelete");
        String col = target(req);

        Map<String, Object> filter = normalizeFilter(req.get("filter"));
        if (filter == null || filter.isEmpty()) {
            throw new Invalid("Filter is required for DELETE. Mass deletion with empty filter is not allowed.");
        }

        List<Map<String, Object>> matched = store.find(col, filter);
        int count = 0;
        for (Map<String, Object> rec : matched) {
            Object id = rec.get("id");
            if (id instanceof String sid && store.deleteById(col, sid)) {
                eventBus.publish(new Event("delete", col, Map.of("id", sid)));
                count++;
            }
        }
        return ok(rid, count + " record(s) deleted",
                  List.of(Map.of("deletedCount", (long) count)));
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

        // Koleksiyon olusturulurken alan (feature) tanimlari da verilebilir.
        // Verilirse bu tipler sonraki kayitlarda zorunlu tutulur (Ister_0014).
        Object fields = mapOrEmpty(req.get("document")).get("fields");
        if (fields instanceof List<?> list && !list.isEmpty()) {
            saveSchema(db, col, new ArrayList<>(list));
        }

        eventBus.publish(new Event("insert", Store.key(db, col), Map.of("collection", col)));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("database", db);
        info.put("collection", col);
        info.put("recordCount", 0L);
        info.put("fields", currentFields(db, col));
        return ok(rid, "Collection created: " + col, List.of(info));
    }

    private String dropCollection(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        boolean dropped = store.dropCollection(db, col);
        if (!dropped) throw new Invalid("Collection not found: " + col);

        eventBus.publish(new Event("delete", Store.key(db, col), Map.of("collection", col)));
        return ok(rid, "Collection dropped: " + col, List.of());
    }

    // ============================================================
    //  KULLANICI YONETIMI (Ister_0004, Ister_0005)
    // ============================================================

    private String listUsers(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        Map<String, Object> filter = mapOrEmpty(req.get("filter"));
        boolean includeDeleted = Boolean.TRUE.equals(filter.get("includeDeleted"));

        List<Map<String, Object>> users = new ArrayList<>();
        for (User u : (includeDeleted ? auth.allUsers() : auth.activeUsers())) {
            users.add(u.toPublicMap());
        }
        return ok(rid, users.size() + " user(s)", users);
    }

    /** Ister_0005: super admin yeni kullanici ekler. */
    private String createUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        Map<String, Object> doc = mapOrEmpty(req.get("document"));

        String email = str(doc, "email");
        if (email == null) throw new Invalid("document.email is required");

        User created = new User(
                auth.newUserId(),
                strOr(doc, "name", ""),
                email,
                strOr(doc, "password", "changeme"),
                strOr(doc, "role", "user"),
                stringSet(doc.get("departments")),
                stringSet(doc.get("permissions")));

        if (!auth.createUser(created)) throw new Invalid("Email already exists: " + email);

        audit.record("userCreated", admin.id(), admin.name(),
                created.id(), created.name(),
                "Kullanici olusturuldu: " + email,
                Map.of(), created.toPublicMap(), false);

        return ok(rid, "User created: " + email, List.of(created.toPublicMap()));
    }

    /** Kullanicinin ad, rol, departman ve yetkilerini gunceller. */
    private String updateUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        User before = auth.byId(id);
        if (before == null) throw new Invalid("User not found: " + id);

        Map<String, Object> oldValues = before.toPublicMap();
        Map<String, Object> doc = mapOrEmpty(req.get("document"));

        Boolean isActive = doc.containsKey("isActive")
                ? Boolean.TRUE.equals(doc.get("isActive")) : null;

        User updated = auth.updateUser(id,
                str(doc, "name"),
                str(doc, "role"),
                doc.containsKey("departments") ? stringSet(doc.get("departments")) : null,
                doc.containsKey("permissions") ? stringSet(doc.get("permissions")) : null,
                isActive);

        String code = (isActive != null && oldValues.get("isActive") != doc.get("isActive"))
                ? "userStatusChanged" : "userUpdated";

        audit.record(code, admin.id(), admin.name(),
                updated.id(), updated.name(),
                "Kullanici guncellendi: " + updated.email(),
                oldValues, updated.toPublicMap(), true);

        return ok(rid, "User updated: " + updated.email(), List.of(updated.toPublicMap()));
    }

    /** Ister_0004: super admin, kullanicinin yetkilerini belirler. */
    private String updateUserPermissions(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        User before = auth.byId(id);
        if (before == null) throw new Invalid("User not found: " + id);

        Map<String, Object> oldValues = before.toPublicMap();
        Map<String, Object> doc = mapOrEmpty(req.get("document"));

        User updated = auth.updatePermissions(id,
                doc.containsKey("departments") ? stringSet(doc.get("departments")) : null,
                doc.containsKey("permissions") ? stringSet(doc.get("permissions")) : null);

        audit.record("permissionsUpdated", admin.id(), admin.name(),
                updated.id(), updated.name(),
                "Yetkiler guncellendi: " + updated.email(),
                oldValues, updated.toPublicMap(), true);

        return ok(rid, "Permissions updated: " + updated.email(),
                  List.of(updated.toPublicMap()));
    }

    /** Yumusak silme: kullanici listelerde gorunmez ama geri alinabilir. */
    private String deleteUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        if (id.equals(admin.id())) throw new Invalid("You cannot delete your own account");

        User before = auth.byId(id);
        if (before == null) throw new Invalid("User not found: " + id);
        Map<String, Object> oldValues = before.toPublicMap();

        User deleted = auth.softDelete(id);
        audit.record("userSoftDeleted", admin.id(), admin.name(),
                deleted.id(), deleted.name(),
                "Kullanici cop kutusuna tasindi: " + deleted.email(),
                oldValues, deleted.toPublicMap(), true);

        return ok(rid, "User moved to trash: " + deleted.email(),
                  List.of(deleted.toPublicMap()));
    }

    private String restoreUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        User restored = auth.restore(id);
        if (restored == null) throw new Invalid("User not found: " + id);

        audit.record("userRestored", admin.id(), admin.name(),
                restored.id(), restored.name(),
                "Kullanici geri alindi: " + restored.email(),
                Map.of(), restored.toPublicMap(), false);

        return ok(rid, "User restored: " + restored.email(),
                  List.of(restored.toPublicMap()));
    }

    /** Kalici silme: kullanici tamamen kaldirilir, geri alinamaz. */
    private String dropUser(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        if (id.equals(admin.id())) throw new Invalid("You cannot delete your own account");

        User before = auth.byId(id);
        if (before == null) throw new Invalid("User not found: " + id);
        Map<String, Object> oldValues = before.toPublicMap();

        auth.hardDelete(id);
        audit.record("userPermanentlyDeleted", admin.id(), admin.name(),
                id, String.valueOf(oldValues.get("name")),
                "Kullanici kalici olarak silindi: " + oldValues.get("email"),
                oldValues, Map.of(), false);

        return ok(rid, "User permanently deleted", List.of());
    }

    /** Sifre sifirlar; yeni sifre verilmezse rastgele uretilir ve dondurulur. */
    private String resetUserPassword(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);
        String id = targetUserId(req);
        User target = auth.byId(id);
        if (target == null) throw new Invalid("User not found: " + id);

        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        String newPassword = auth.resetPassword(id, str(doc, "password"));

        audit.record("passwordResetRequested", admin.id(), admin.name(),
                target.id(), target.name(),
                "Sifre sifirlandi: " + target.email(),
                Map.of(), Map.of(), false);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.id());
        result.put("email", target.email());
        result.put("password", newPassword);   // super admin kullaniciya iletir
        return ok(rid, "Password reset for " + target.email(), List.of(result));
    }

    /** Istekten hedef kullanici kimligini cikarir: filter.id > document.id. */
    private static String targetUserId(Map<String, Object> req) {
        String id = str(mapOrEmpty(req.get("filter")), "id");
        if (id == null) id = str(mapOrEmpty(req.get("document")), "id");
        if (id == null) throw new Invalid("filter.id is required");
        return id;
    }

    // ============================================================
    //  DENETIM KAYITLARI
    // ============================================================

    private String auditLogs(String rid, User user, Map<String, Object> req) {
        requireSuperAdmin(user);
        Map<String, Object> filter = mapOrEmpty(req.get("filter"));

        String action = str(filter, "action");
        Boolean onlyRevertible = filter.containsKey("onlyRevertible")
                ? Boolean.TRUE.equals(filter.get("onlyRevertible")) : null;
        int limit = intOr(filter.get("limit"), 0);

        List<Map<String, Object>> logs = audit.list(action, onlyRevertible, limit);
        return ok(rid, logs.size() + " log(s)", logs);
    }

    private String recentActivities(String rid, User user, Map<String, Object> req) {
        if (!user.can("databaseView")) throw new Denied("Permission denied: databaseView");
        int limit = intOr(mapOrEmpty(req.get("filter")).get("limit"), 10);
        return ok(rid, "Recent activities", audit.recentActivities(limit));
    }

    /**
     * Bir denetim kaydini geri alir.
     *
     * Yalnizca kullanici islemleri geri alinabilir: kaydin "oldValues"
     * alanindaki eski durum kullaniciya yeniden yazilir.
     */
    private String revertAuditLog(String rid, User admin, Map<String, Object> req) {
        requireSuperAdmin(admin);

        String logId = str(mapOrEmpty(req.get("filter")), "logId");
        if (logId == null) logId = str(mapOrEmpty(req.get("filter")), "id");
        if (logId == null) throw new Invalid("filter.logId is required");

        Map<String, Object> log = audit.byId(logId);
        if (log == null) throw new Invalid("Audit log not found: " + logId);
        if (Boolean.TRUE.equals(log.get("isReverted"))) {
            throw new Invalid("This log has already been reverted");
        }
        if (!Boolean.TRUE.equals(log.get("isRevertible"))) {
            throw new Invalid("This operation cannot be reverted");
        }

        Map<String, Object> oldValues = mapOrEmpty(log.get("oldValues"));
        String targetId = str(log, "targetUserId");
        if (targetId == null || oldValues.isEmpty()) {
            throw new Invalid("This log does not contain enough information to revert");
        }

        User target = auth.byId(targetId);
        if (target == null) throw new Invalid("Target user no longer exists");

        // Eski durumu geri yaz
        target.setName(str(oldValues, "name"));
        target.setRole(str(oldValues, "role"));
        target.setDepartments(stringSet(oldValues.get("departments")));
        target.setPermissions(stringSet(oldValues.get("permissions")));
        target.setActive(!Boolean.FALSE.equals(oldValues.get("isActive")));
        target.setDeleted(Boolean.TRUE.equals(oldValues.get("isDeleted")));
        auth.save(target);

        audit.markReverted(logId, admin.name());
        audit.record("permissionsReverted", admin.id(), admin.name(),
                target.id(), target.name(),
                "Islem geri alindi: " + log.get("description"),
                Map.of(), target.toPublicMap(), false);

        return ok(rid, "Reverted", List.of(target.toPublicMap()));
    }

    private static int intOr(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    // ============================================================
    //  TALEP DOSYASI (Ister_0011, Ister_0012)
    // ============================================================

    /**
     * Ister_0011: JSON formatinda olusturulan veritabani dosyasinin,
     * dinamik olarak belirlenen dosya yolunda olup olmadigini kontrol eder.
     *
     * Istek: {"action":"CHECK_FILE", ..., "document":{"path":"okul.json"}}
     * Cevap data[0]: {exists, isFile, readable, validJson, size, path, ...}
     *
     * Yol, sunucudaki talep klasoru altinda cozulur; disari cikan yollar
     * reddedilir.
     */
    private String checkFile(String rid, User user, Map<String, Object> req) {
        require(user, "databaseView");
        Map<String, Object> info = files.check(filePath(req));
        boolean exists = Boolean.TRUE.equals(info.get("exists"));
        String message = exists ? "File found" : "File not found";
        return ok(rid, message, List.of(info));
    }

    /**
     * Ister_0012: JSON dosyasinin icerigine gore veritabaninda alan yaratir.
     *
     * Beklenen dosya bicimi:
     *   {
     *     "database": "okul",
     *     "department": "Egitim",
     *     "description": "...",
     *     "collections": [
     *       {"name":"ogrenciler",
     *        "fields":[{"name":"ad","type":"string"},{"name":"sinif","type":"int"}],
     *        "records":[{"ad":"Ali","sinif":3}]}
     *     ]
     *   }
     *
     * "records" istege baglidir; verilirse baslangic kayitlari eklenir.
     */
    @SuppressWarnings("unchecked")
    private String importFile(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");

        Map<String, Object> content = files.readJson(filePath(req));

        String dbName = str(content, "database");
        if (dbName == null) throw new Invalid("File must contain a 'database' field");
        if (META_DB.equals(dbName)) throw new Invalid("Reserved database name: " + META_DB);

        // 1) Veritabani ust verisi (yoksa olustur)
        Map<String, Object> meta = findDatabaseMeta(dbName);
        if (meta == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", dbName);
            data.put("department", strOr(content, "department", ""));
            data.put("description", strOr(content, "description", ""));
            data.put("isDeleted", false);
            data.put("deletedBy", null);
            meta = store.insert(META_DATABASES, data);
            eventBus.publish(new Event("insert", META_DATABASES, meta));
        }

        // 2) Koleksiyonlar, alan tanimlari ve baslangic kayitlari
        List<String> createdCollections = new ArrayList<>();
        long insertedRecords = 0;

        Object colsObj = content.get("collections");
        if (colsObj instanceof List<?> cols) {
            for (Object c : cols) {
                if (!(c instanceof Map)) continue;
                Map<String, Object> col = (Map<String, Object>) c;

                String colName = str(col, "name");
                if (colName == null) continue;

                if (store.createCollection(dbName, colName)) {
                    createdCollections.add(colName);
                    eventBus.publish(new Event("insert", Store.key(dbName, colName),
                            Map.of("collection", colName)));
                }

                // Alan tanimlarini sakla (Ister_0012'nin "alan yaratma" karsiligi;
                // MongoDB semasiz oldugu icin tanimlar ust veride tutulur)
                Object fields = col.get("fields");
                if (fields instanceof List) {
                    saveSchema(dbName, colName, (List<Object>) fields);
                }

                // Baslangic kayitlari
                Object recs = col.get("records");
                if (recs instanceof List<?> list) {
                    for (Object rec : list) {
                        if (rec instanceof Map) {
                            Map<String, Object> inserted =
                                    store.insert(Store.key(dbName, colName), (Map<String, Object>) rec);
                            eventBus.publish(new Event("insert", Store.key(dbName, colName), inserted));
                            insertedRecords++;
                        }
                    }
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("database", dbName);
        summary.put("collectionsCreated", createdCollections);
        summary.put("collectionCount", (long) createdCollections.size());
        summary.put("recordsInserted", insertedRecords);
        return ok(rid, "Import completed: " + dbName, List.of(summary));
    }

    /**
     * Bir koleksiyonun alan tanimlarini doner (IMPORT_FILE ile kaydedilmis).
     * On Yuz'un veri tipi ekrani icin kullanilir.
     */
    private String describeCollection(String rid, User user, Map<String, Object> req) {
        require(user, "databaseView");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        List<Map<String, Object>> found = store.find(META_SCHEMAS,
                Map.of("database", db, "collection", col));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", db);
        result.put("collection", col);
        result.put("fields", found.isEmpty() ? List.of() : found.get(0).getOrDefault("fields", List.of()));
        return ok(rid, "Schema for " + Store.key(db, col), List.of(result));
    }

    /**
     * Ister_0014: gelen kaydin alan tiplerini kayitli semaya gore dogrular.
     * Sema tanimlanmamis koleksiyonlar serbesttir.
     */
    private void validateAgainstSchema(String database, String collection,
                                       Map<String, Object> document) {
        if (database == null || collection == null) return;

        List<Map<String, Object>> schemas = store.find(META_SCHEMAS,
                Map.of("database", database, "collection", collection));
        if (schemas.isEmpty()) return;

        Object fields = schemas.get(0).get("fields");
        if (!(fields instanceof List<?> list)) return;

        List<String> errors = SchemaValidator.validate(list, document);
        if (!errors.isEmpty()) {
            throw new Invalid("Invalid data format: " + String.join("; ", errors));
        }
    }

    /**
     * Alan (feature) tanimlarini ekler veya gunceller.
     *
     * Istek:
     *   {"action":"DEFINE_FIELDS", ..., "database":"ev","collection":"odalar",
     *    "document":{"fields":[{"name":"oda","type":"int"},
     *                          {"name":"ad","type":"string"}]}}
     *
     * Ayni adli alan zaten tanimliysa tipi GUNCELLENIR. Bu, kullanicinin
     * "yanlis tip secmisim" durumunu duzeltmesini saglar; ancak daha once
     * yazilmis kayitlar oldugu gibi kalir — gerekirse koleksiyon
     * temizlenmelidir.
     *
     * Tek bir alan icin document.name + document.type de kullanilabilir.
     */
    @SuppressWarnings("unchecked")
    private String defineFields(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        List<Object> incoming = new ArrayList<>();

        Object fields = doc.get("fields");
        if (fields instanceof List<?> list) {
            incoming.addAll(list);
        } else if (str(doc, "name") != null) {
            // Tek alan bicimi
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("name", str(doc, "name"));
            single.put("type", strOr(doc, "type", "any"));
            incoming.add(single);
        }
        if (incoming.isEmpty()) {
            throw new Invalid("document.fields (or document.name + document.type) is required");
        }

        // Desteklenmeyen tip erken yakalanir; yoksa sessizce "kontrol edilmez"e duserdi
        for (Object o : incoming) {
            if (!(o instanceof Map)) continue;
            String type = str((Map<String, Object>) o, "type");
            if (type != null && !SUPPORTED_TYPES.contains(type.toLowerCase())) {
                throw new Invalid("Unsupported field type: " + type
                        + " (supported: " + String.join(", ", SUPPORTED_TYPES) + ")");
            }
        }

        List<Object> merged = mergeFields(currentFields(db, col), incoming);
        saveSchema(db, col, merged);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", db);
        result.put("collection", col);
        result.put("fields", merged);
        return ok(rid, incoming.size() + " field(s) defined", List.of(result));
    }

    /**
     * Bir alan tanimini kaldirir. Kayitlardaki veriye dokunmaz; yalnizca
     * o alan icin tip kontrolu yapilmaz hale gelir.
     */
    @SuppressWarnings("unchecked")
    private String deleteField(String rid, User user, Map<String, Object> req) {
        require(user, "databaseCreate");
        String db = databaseName(req);
        String col = str(req, "collection");
        if (col == null) throw new Invalid("collection field is required");

        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        String name = str(doc, "name");
        if (name == null) name = str(mapOrEmpty(req.get("filter")), "name");
        if (name == null) throw new Invalid("document.name is required");

        List<Object> remaining = new ArrayList<>();
        boolean found = false;
        for (Object o : currentFields(db, col)) {
            if (o instanceof Map && name.equals(str((Map<String, Object>) o, "name"))) {
                found = true;
                continue;
            }
            remaining.add(o);
        }
        if (!found) throw new Invalid("Field not found: " + name);

        saveSchema(db, col, remaining);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database", db);
        result.put("collection", col);
        result.put("fields", remaining);
        return ok(rid, "Field removed: " + name, List.of(result));
    }

    /** Bir koleksiyonun kayitli alan tanimlari; yoksa bos liste. */
    @SuppressWarnings("unchecked")
    private List<Object> currentFields(String database, String collection) {
        List<Map<String, Object>> found = store.find(META_SCHEMAS,
                Map.of("database", database, "collection", collection));
        if (found.isEmpty()) return new ArrayList<>();
        Object fields = found.get(0).get("fields");
        return (fields instanceof List<?> list) ? new ArrayList<>(list) : new ArrayList<>();
    }

    /** Yeni tanimlari mevcutlarla birlestirir; ayni adli alan uzerine yazilir. */
    @SuppressWarnings("unchecked")
    private static List<Object> mergeFields(List<Object> existing, List<Object> incoming) {
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Object o : existing) {
            if (o instanceof Map && str((Map<String, Object>) o, "name") != null) {
                byName.put(str((Map<String, Object>) o, "name"), o);
            }
        }
        for (Object o : incoming) {
            if (o instanceof Map && str((Map<String, Object>) o, "name") != null) {
                byName.put(str((Map<String, Object>) o, "name"), o);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * Ister_0014'un tamamlayicisi: henuz tanimlanmamis bir alan ilk kez
     * yazildiginda tipini kaydeder (kilitler).
     *
     * Boylece frontend disindan gelen yazmalar (orn. Arka Yuz kutuphanesi)
     * da korunur; ilk gelen tip o alanin tipi olur ve sonraki farkli tipler
     * reddedilir.
     *
     * config.xml'de <AutoSchema>false</AutoSchema> ile kapatilabilir.
     */
    @SuppressWarnings("unchecked")
    private void learnFieldTypes(String database, String collection,
                                 Map<String, Object> document) {
        if (!autoSchema || database == null || collection == null) return;

        List<Object> known = currentFields(database, collection);
        java.util.Set<String> knownNames = new java.util.LinkedHashSet<>();
        for (Object o : known) {
            if (o instanceof Map) {
                String n = str((Map<String, Object>) o, "name");
                if (n != null) knownNames.add(n);
            }
        }

        List<Object> learned = new ArrayList<>();
        for (Map.Entry<String, Object> e : document.entrySet()) {
            String name = e.getKey();
            if (knownNames.contains(name)) continue;
            if (e.getValue() == null) continue;   // tip cikarilamaz

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("type", inferType(e.getValue()));
            field.put("inferred", true);   // elle tanimlanmadigini belirtir
            learned.add(field);
        }

        if (!learned.isEmpty()) {
            known.addAll(learned);
            saveSchema(database, collection, known);
        }
    }

    /** Bir degerin tipini protokol adlariyla adlandirir. */
    private static String inferType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Long || value instanceof Integer) return "int";
        if (value instanceof Double) return "double";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return "any";
    }

    /** Alan tanimlarini kaydeder; ayni koleksiyon icin varsa gunceller. */
    private void saveSchema(String database, String collection, List<Object> fields) {
        List<Map<String, Object>> existing = store.find(META_SCHEMAS,
                Map.of("database", database, "collection", collection));

        if (existing.isEmpty()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("database", database);
            doc.put("collection", collection);
            doc.put("fields", fields);
            store.insert(META_SCHEMAS, doc);
        } else {
            store.updateById(META_SCHEMAS, (String) existing.get(0).get("id"),
                    Map.of("fields", fields));
        }
    }

    /** Dosya yolunu istekten cikarir: document.path > filter.path > "path". */
    private static String filePath(Map<String, Object> req) {
        Map<String, Object> doc = mapOrEmpty(req.get("document"));
        String path = str(doc, "path");
        if (path == null) path = str(mapOrEmpty(req.get("filter")), "path");
        if (path == null) path = str(req, "path");
        if (path == null) throw new Invalid("'path' is required (in document.path)");
        return path;
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

    /** On Yuz panosu icin sistem durumu. */
    private String systemStatus(String rid) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("isMongoConnected", store.isHealthy());
        status.put("isApiOnline", true);   // bu cevabi uretebiliyorsak ayaktayiz
        status.put("lastBackupAt", null);
        status.put("lastCheckedAt",
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return ok(rid, "System status", List.of(status));
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
        return Store.key(str(req, "database"), col);
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
            Object n = info.get(Store.key(name, c));
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

    /**
     * Filtreyi normallestirir.
     *
     * Kayitlarimizda "_id" alani ASLA bulunmaz (MongoDB'nin ObjectId'si
     * disarı verilmez, kendi "id" alanimiz kullanilir). Bu yuzden "_id"
     * iceren bir filtre hicbir kaydi bulamaz. MongoDB alistigi icin "_id"
     * gonderen istemcileri desteklemek adina bunu "id" olarak yorumluyoruz.
     *
     * Ornek: {"id":"rec-8","_id":"rec-8"} -> {"id":"rec-8"}
     */
    // private static Map<String, Object> normalizeFilter(Object raw) {
    //     Map<String, Object> filter = mapOrNull(raw);
    //     if (filter == null || !filter.containsKey("_id")) return filter;

    //     Map<String, Object> normalized = new LinkedHashMap<>(filter);
    //     Object underscoreId = normalized.remove("_id");
    //     normalized.putIfAbsent("id", underscoreId);
    //     return normalized;
    // }

    private static Map<String, Object> normalizeFilter(Object raw) {
        Map<String, Object> filter = mapOrNull(raw);
        if (filter == null) return null;

        // GUVENLIK KONTROLU: READ, UPDATE, DELETE ortak korumasi
        try {
            FilterSanitizer.validate(filter);
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage());
        }

        if (!filter.containsKey("_id")) return filter;

        Map<String, Object> normalized = new LinkedHashMap<>(filter);
        Object underscoreId = normalized.remove("_id");
        normalized.putIfAbsent("id", underscoreId);
        return normalized;
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
