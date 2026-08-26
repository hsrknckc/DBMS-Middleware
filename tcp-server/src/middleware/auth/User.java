package middleware.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sistemdeki bir kullaniciyi ve yetkilerini temsil eder.
 *
 * Yetki isimleri On Yuz'un Permission listesiyle BIREBIR aynidir
 * (databaseView, databaseCreate, dataView, dataCreate, dataUpdate,
 * dataDelete, dataImport, dataExport). Rol iki turdur: "superAdmin"
 * her seyi yapabilir; "user" yalnizca kendisine verilen yetkilerle sinirlidir.
 *
 * KALICILIK: toStorageMap() / fromStorageMap() ile veri deposuna yazilip
 * okunur; boylece sunucu yeniden baslasa da kullanicilar korunur.
 */
public class User {

    private final String id;
    private String name;
    private final String email;
    private String password;
    private String role;
    private Set<String> departments;
    private Set<String> permissions;

    private Map<String, List<String>> allowedCollections;
    private Map<String, Set<String>> databasePermissions;
    private Map<String, Set<String>> collectionPermissions;

    private boolean active = true;
    private boolean deleted = false;

    public User(String id, String name, String email, String password,
                String role, Set<String> departments, Set<String> permissions) {
        this(
                id,
                name,
                email,
                password,
                role,
                departments,
                permissions,
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    public User(String id, String name, String email, String password,
                String role, Set<String> departments, Set<String> permissions,
                Map<String, List<String>> allowedCollections,
                Map<String, Set<String>> databasePermissions,
                Map<String, Set<String>> collectionPermissions) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
        this.departments = new LinkedHashSet<>(departments);
        this.permissions = new LinkedHashSet<>(permissions);
        this.allowedCollections = copyStringListMap(allowedCollections);
        this.databasePermissions = copyStringSetMap(databasePermissions);
        this.collectionPermissions = copyStringSetMap(collectionPermissions);
    }

    // --- okuma ---

    public String id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public String role() { return role; }
    public boolean active() { return active; }
    public boolean deleted() { return deleted; }

    public Set<String> departments() {
        return new LinkedHashSet<>(departments);
    }

    public Set<String> permissions() {
        return new LinkedHashSet<>(permissions);
    }

    public Map<String, List<String>> allowedCollections() {
        return copyStringListMap(allowedCollections);
    }

    public Map<String, Set<String>> databasePermissions() {
        return copyStringSetMap(databasePermissions);
    }

    public Map<String, Set<String>> collectionPermissions() {
        return copyStringSetMap(collectionPermissions);
    }

    // --- degistirme ---

    public void setName(String name) {
        if (name != null) this.name = name;
    }

    public void setRole(String role) {
        if (role != null) this.role = role;
    }

    public void setPassword(String password) {
        if (password != null) this.password = password;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public void setDepartments(Set<String> departments) {
        if (departments != null) this.departments = new LinkedHashSet<>(departments);
    }

    public void setPermissions(Set<String> permissions) {
        if (permissions != null) this.permissions = new LinkedHashSet<>(permissions);
    }

    public void setAllowedCollections(Map<String, List<String>> allowedCollections) {
        if (allowedCollections != null) {
            this.allowedCollections = copyStringListMap(allowedCollections);
        }
    }

    public void setDatabasePermissions(Map<String, Set<String>> databasePermissions) {
        if (databasePermissions != null) {
            this.databasePermissions = copyStringSetMap(databasePermissions);
        }
    }

    public void setCollectionPermissions(Map<String, Set<String>> collectionPermissions) {
        if (collectionPermissions != null) {
            this.collectionPermissions = copyStringSetMap(collectionPermissions);
        }
    }

    // --- kimlik ve yetki ---

    public boolean passwordMatches(String candidate) {
        return password.equals(candidate);
    }

    public boolean isSuperAdmin() {
        return "superAdmin".equals(role);
    }

    /** Eski global yetki sistemi. Geriye uyumluluk icin korunur. */
    public boolean can(String permission) {
        return isSuperAdmin() || permissions.contains(permission);
    }

    /** Database bazli yetki kontrolu. */
    public boolean canDatabase(String database, String permission) {
        if (isSuperAdmin() || permissions.contains(permission)) {
            return true;
        }

        if (database == null) {
            return false;
        }

        Set<String> scoped = databasePermissions.get(database);
        return scoped != null && scoped.contains(permission);
    }

    /** Collection bazli yetki kontrolu. */
    public boolean canCollection(String database, String collection, String permission) {
        if (isSuperAdmin() || permissions.contains(permission)) {
            return true;
        }

        if (database == null || collection == null) {
            return false;
        }

        if (!isCollectionAllowed(database, collection)) {
            return false;
        }

        Set<String> dbScoped = databasePermissions.get(database);
        if (dbScoped != null && dbScoped.contains(permission)) {
            return true;
        }

        Set<String> colScoped = collectionPermissions.get(collectionKey(database, collection));
        return colScoped != null && colScoped.contains(permission);
    }

    private boolean isCollectionAllowed(String database, String collection) {
        if (allowedCollections.isEmpty()) {
            return true;
        }

        List<String> collections = allowedCollections.get(database);
        return collections != null && collections.contains(collection);
    }

    public boolean canLogin() {
        return active && !deleted;
    }

    // --- disari verme ---

    public Map<String, Object> toPublicMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("role", role);
        m.put("departments", List.copyOf(departments));
        m.put("permissions", List.copyOf(permissions));
        m.put("allowedCollections", serializeStringListMap(allowedCollections));
        m.put("databasePermissions", serializePermissionMap(databasePermissions));
        m.put("collectionPermissions", serializePermissionMap(collectionPermissions));
        m.put("isActive", active);
        m.put("isDeleted", deleted);
        return m;
    }

    public Map<String, Object> toStorageMap() {
        Map<String, Object> m = new LinkedHashMap<>(toPublicMap());
        m.put("userId", id);
        m.put("password", password);
        return m;
    }

    public static User fromStorageMap(Map<String, Object> m) {
        User u = new User(
                String.valueOf(m.getOrDefault("userId", m.getOrDefault("id", ""))),
                String.valueOf(m.getOrDefault("name", "")),
                String.valueOf(m.getOrDefault("email", "")),
                String.valueOf(m.getOrDefault("password", "")),
                String.valueOf(m.getOrDefault("role", "user")),
                toSet(m.get("departments")),
                toSet(m.get("permissions")),
                toStringListMap(m.get("allowedCollections")),
                toPermissionMap(m.get("databasePermissions")),
                toPermissionMap(m.get("collectionPermissions"))
        );

        u.active = !Boolean.FALSE.equals(m.get("isActive"));
        u.deleted = Boolean.TRUE.equals(m.get("isDeleted"));
        return u;
    }

    private static Set<String> toSet(Object o) {
        Set<String> set = new LinkedHashSet<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x != null) set.add(x.toString());
            }
        }
        return set;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> toStringListMap(Object o) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        if (!(o instanceof Map<?, ?> raw)) {
            return result;
        }

        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;

            List<String> values = new java.util.ArrayList<>();
            Object value = entry.getValue();

            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) values.add(item.toString());
                }
            }

            result.put(entry.getKey().toString(), List.copyOf(values));
        }

        return result;
    }

    private static Map<String, Set<String>> toPermissionMap(Object o) {
        Map<String, Set<String>> result = new LinkedHashMap<>();

        if (!(o instanceof Map<?, ?> raw)) {
            return result;
        }

        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;

            Set<String> values = new LinkedHashSet<>();
            Object value = entry.getValue();

            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) values.add(item.toString());
                }
            }

            result.put(entry.getKey().toString(), values);
        }

        return result;
    }

    private static Map<String, List<String>> copyStringListMap(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();

        if (source == null) {
            return copy;
        }

        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return copy;
    }

    private static Map<String, Set<String>> copyStringSetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();

        if (source == null) {
            return copy;
        }

        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }

        return copy;
    }

    private static Map<String, Object> serializeStringListMap(Map<String, List<String>> source) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            out.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return out;
    }

    private static Map<String, Object> serializePermissionMap(Map<String, Set<String>> source) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            out.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return out;
    }

    private static String collectionKey(String database, String collection) {
        return database + "/" + collection;
    }
}