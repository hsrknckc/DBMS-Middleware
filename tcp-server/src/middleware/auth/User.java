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
 * Alanlar degistirilebilir cunku super admin bir kullanicinin adini,
 * rolunu, yetkilerini ve sifresini guncelleyebilir (Ister_0004).
 *
 * KALICILIK: toStorageMap() / fromStorageMap() ile veri deposuna yazilip
 * okunur; boylece sunucu yeniden baslasa da kullanicilar korunur.
 */
public class User {

    private final String id;
    private String name;
    private final String email;         // kimlik gorevi gorur, degistirilemez
    private String password;            // demo amacli duz metin; gercekte hash saklanir
    private String role;                // "superAdmin" | "user"
    private Set<String> departments;
    private Set<String> permissions;
    private boolean active = true;
    private boolean deleted = false;    // yumusak silme (geri alinabilir)

    public User(String id, String name, String email, String password,
                String role, Set<String> departments, Set<String> permissions) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
        this.departments = new LinkedHashSet<>(departments);
        this.permissions = new LinkedHashSet<>(permissions);
    }

    // --- okuma ---

    public String id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public String role() { return role; }
    public boolean active() { return active; }
    public boolean deleted() { return deleted; }
    public Set<String> departments() { return new LinkedHashSet<>(departments); }
    public Set<String> permissions() { return new LinkedHashSet<>(permissions); }

    // --- degistirme (super admin islemleri) ---

    public void setName(String name) { if (name != null) this.name = name; }
    public void setRole(String role) { if (role != null) this.role = role; }
    public void setPassword(String password) { if (password != null) this.password = password; }
    public void setActive(boolean active) { this.active = active; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public void setDepartments(Set<String> departments) {
        if (departments != null) this.departments = new LinkedHashSet<>(departments);
    }

    public void setPermissions(Set<String> permissions) {
        if (permissions != null) this.permissions = new LinkedHashSet<>(permissions);
    }

    // --- kimlik ve yetki ---

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

    /** Giris yapabilir mi? Pasif ya da silinmis kullanici giremez. */
    public boolean canLogin() {
        return active && !deleted;
    }

    // --- disari verme ---

    /**
     * Kullaniciyi On Yuz'un bekledigi JSON bicimine cevirir.
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
        m.put("isDeleted", deleted);
        return m;
    }

    /**
     * Veri deposuna yazmak icin; sifre DAHIL edilir.
     *
     * DIKKAT: depo her kayda kendi "id" alanini atar, bu yuzden kullanici
     * kimligi ayrica "userId" altinda saklanir — aksi halde kimlik ezilir
     * ve her guncelleme yeni satir olusturur.
     */
    public Map<String, Object> toStorageMap() {
        Map<String, Object> m = new LinkedHashMap<>(toPublicMap());
        m.put("userId", id);
        m.put("password", password);
        return m;
    }

    /** Veri deposundan okunan kaydi User nesnesine cevirir. */
    public static User fromStorageMap(Map<String, Object> m) {
        User u = new User(
                String.valueOf(m.getOrDefault("userId", m.getOrDefault("id", ""))),
                String.valueOf(m.getOrDefault("name", "")),
                String.valueOf(m.getOrDefault("email", "")),
                String.valueOf(m.getOrDefault("password", "")),
                String.valueOf(m.getOrDefault("role", "user")),
                toSet(m.get("departments")),
                toSet(m.get("permissions")));
        u.active = !Boolean.FALSE.equals(m.get("isActive"));
        u.deleted = Boolean.TRUE.equals(m.get("isDeleted"));
        return u;
    }

    private static Set<String> toSet(Object o) {
        Set<String> set = new LinkedHashSet<>();
        if (o instanceof List<?> list) {
            for (Object x : list) if (x != null) set.add(x.toString());
        }
        return set;
    }
}
