package middleware.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sistemdeki bir kullaniciyi ve yetkilerini temsil eder.
 *
 * Yetki isimleri frontend'in Permission enum'i ile BIREBIR aynidir
 * (databaseView, databaseCreate, dataView, dataCreate, dataUpdate,
 * dataDelete, dataImport, dataExport). Rol iki turdur: "superAdmin"
 * her seyi yapabilir; "user" yalnizca kendisine verilen yetkilerle sinirlidir.
 */
public class User {

    private final String id;
    private final String name;
    private final String email;
    private final String password;      // demo amacli duz metin; gercekte hash saklanir
    private final String role;          // "superAdmin" | "user"
    private final Set<String> departments;
    private final Set<String> permissions;
    private boolean active = true;

    public User(String id, String name, String email, String password,
                String role, Set<String> departments, Set<String> permissions) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
        this.departments = departments;
        this.permissions = permissions;
    }

    public String id() { return id; }
    public String email() { return email; }
    public String role() { return role; }
    public boolean active() { return active; }
    public void setActive(boolean a) { this.active = a; }

    public boolean passwordMatches(String candidate) {
        return password.equals(candidate);
    }

    public boolean isSuperAdmin() {
        return "superAdmin".equals(role);
    }

    /** Super admin her yetkiye sahiptir; digerleri sadece verilen yetkilere. */
    public boolean can(String permission) {
        return isSuperAdmin() || permissions.contains(permission);
    }

    /**
     * Kullaniciyi frontend'in bekledigi JSON bicimine cevirir.
     * Sifre ASLA disari verilmez.
     */
    public Map<String, Object> toPublicMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("email", email);
        m.put("role", role);
        m.put("departments", List.copyOf(departments));
        m.put("permissions", List.copyOf(permissions));
        m.put("isActive", active);
        return m;
    }
}
