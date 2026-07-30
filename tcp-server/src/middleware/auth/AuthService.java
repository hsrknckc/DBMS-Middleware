package middleware.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import middleware.storage.Store;

/**
 * Kimlik dogrulama ve kullanici yonetimi (Ister_0002 - Ister_0005).
 *
 * KALICILIK
 * Kullanicilar veri deposunda "__meta__/users" koleksiyonunda tutulur;
 * sunucu yeniden baslasa da korunurlar. Bellekteki haritalar yalnizca
 * hizli erisim icin onbellektir — her degisiklikte depoya da yazilir.
 *
 * Depo bosken iki demo kullanici olusturulur (ilk kurulum).
 */
public class AuthService {

    /** Kullanicilarin saklandigi ayrilmis koleksiyon. */
    public static final String USERS_COLLECTION = "__meta__/users";

    private final Store store;
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, User> usersById = new ConcurrentHashMap<>();

    public AuthService(Store store) {
        this.store = store;
        loadFromStore();
    }

    // ============================================================
    //  YUKLEME VE KAYIT
    // ============================================================

    /** Depodaki kullanicilari belleğe alir; depo bossa demo kullanicilari olusturur. */
    private void loadFromStore() {
        List<Map<String, Object>> saved = store.find(USERS_COLLECTION, null);

        if (saved.isEmpty()) {
            seedUsers();
            return;
        }
        for (Map<String, Object> row : saved) {
            User u = User.fromStorageMap(row);
            usersByEmail.put(u.email(), u);
            usersById.put(u.id(), u);
        }
        System.out.println("[auth] " + saved.size() + " kullanici depodan yuklendi");
    }

    /** Ilk kurulum: bir super admin, bir sinirli kullanici. */
    private void seedUsers() {
        User admin = new User(
                "user-1", "Ayse Yilmaz", "ayse@company.com", "Ddsfkln1as1sFd",
                "superAdmin",
                Set.of("Sensor", "Signal", "Acoustic", "Sonar", "Test"),
                Set.of());
        User normal = new User(
                "user-2", "Mehmet Kaya", "mehmet@company.com", "3QPKdvlca34avSl",
                "user",
                Set.of("Sensor"),
                Set.of("databaseView", "dataView", "dataCreate"));

        persist(admin);
        persist(normal);
        System.out.println("[auth] Demo kullanicilar olusturuldu");
    }

    /** Kullaniciyi hem bellege hem depoya yazar. */
    private void persist(User u) {
        usersByEmail.put(u.email(), u);
        usersById.put(u.id(), u);

        List<Map<String, Object>> existing =
                store.find(USERS_COLLECTION, Map.of("userId", u.id()));

        if (existing.isEmpty()) {
            store.insert(USERS_COLLECTION, u.toStorageMap());
        } else {
            store.updateById(USERS_COLLECTION,
                    String.valueOf(existing.get(0).get("id")), u.toStorageMap());
        }
    }

    /** Degisen kullaniciyi depoya yazar (disaridan cagrilir). */
    public void save(User u) {
        persist(u);
    }

    // ============================================================
    //  KIMLIK DOGRULAMA
    // ============================================================

    /**
     * Ister_0002: kullanici adi (tam e-posta) ve sifre ile dogrulama.
     * Pasif veya silinmis kullanici giremez.
     */
    public User authenticate(String username, String password) {
        if (username == null || password == null) return null;
        User u = usersByEmail.get(username);
        if (u == null || !u.canLogin() || !u.passwordMatches(password)) return null;
        return u;
    }

    // ============================================================
    //  OKUMA
    // ============================================================

    public User byEmail(String email) { return usersByEmail.get(email); }
    public User byId(String id) { return usersById.get(id); }

    /** Silinmemis kullanicilar. */
    public List<User> activeUsers() {
        List<User> list = new ArrayList<>();
        for (User u : usersById.values()) if (!u.deleted()) list.add(u);
        return list;
    }

    /** Silinmisler dahil tum kullanicilar. */
    public List<User> allUsers() {
        return new ArrayList<>(usersById.values());
    }

    public int activeUserCount() {
        return (int) usersById.values().stream()
                .filter(u -> u.active() && !u.deleted()).count();
    }

    // ============================================================
    //  YONETIM (Ister_0004, Ister_0005)
    // ============================================================

    /** Ister_0005: yeni kullanici ekler. E-posta zaten varsa false. */
    public boolean createUser(User u) {
        if (usersByEmail.containsKey(u.email())) return false;
        persist(u);
        return true;
    }

    /** Yeni kullanici icin benzersiz kimlik uretir. */
    public String newUserId() {
        return "user-" + UUID.randomUUID();
    }

    /** Ad, rol, aktiflik gibi alanlari gunceller. */
    public User updateUser(String id, String name, String role,
                           Set<String> departments, Set<String> permissions,
                           Boolean isActive) {
        User u = usersById.get(id);
        if (u == null) return null;

        u.setName(name);
        u.setRole(role);
        u.setDepartments(departments);
        u.setPermissions(permissions);
        if (isActive != null) u.setActive(isActive);

        persist(u);
        return u;
    }

    /** Ister_0004: yalnizca departman ve yetkileri gunceller. */
    public User updatePermissions(String id, Set<String> departments, Set<String> permissions) {
        User u = usersById.get(id);
        if (u == null) return null;
        u.setDepartments(departments);
        u.setPermissions(permissions);
        persist(u);
        return u;
    }

    /** Yumusak silme: kullanici listede gorunmez ama geri alinabilir. */
    public User softDelete(String id) {
        User u = usersById.get(id);
        if (u == null) return null;
        u.setDeleted(true);
        persist(u);
        return u;
    }

    public User restore(String id) {
        User u = usersById.get(id);
        if (u == null) return null;
        u.setDeleted(false);
        persist(u);
        return u;
    }

    /** Kalici silme: kullanici tamamen kaldirilir. */
    public boolean hardDelete(String id) {
        User u = usersById.remove(id);
        if (u == null) return false;
        usersByEmail.remove(u.email());

        List<Map<String, Object>> existing =
                store.find(USERS_COLLECTION, Map.of("userId", id));
        if (!existing.isEmpty()) {
            store.deleteById(USERS_COLLECTION, String.valueOf(existing.get(0).get("id")));
        }
        return true;
    }

    /** Sifre sifirlama; yeni sifre verilmezse rastgele uretilir ve dondurulur. */
    public String resetPassword(String id, String newPassword) {
        User u = usersById.get(id);
        if (u == null) return null;

        String password = (newPassword == null || newPassword.isBlank())
                ? generatePassword() : newPassword;
        u.setPassword(password);
        persist(u);
        return password;
    }

    private static String generatePassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
