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
 * MIDDLEWARE_USER ve MIDDLEWARE_PASSWORD environment variable'lari
 * ile tanimlanan kullanici Super Admin olarak olusturulur.
 *
 * Kullanici daha once olusturulmussa sifresi ve Super Admin
 * yetkileri guncellenir.
 */
public class AuthService {

    /** Kullanicilarin saklandigi ayrilmis koleksiyon. */
    public static final String USERS_COLLECTION = "__meta__/users";

    private final Store store;

    private final Map<String, User> usersByEmail =
            new ConcurrentHashMap<>();

    private final Map<String, User> usersById =
            new ConcurrentHashMap<>();

    public AuthService(Store store) {
        this.store = store;
        loadFromStore();
    }

    // ============================================================
    //  YUKLEME VE KAYIT
    // ============================================================

    /**
     * Depodaki kullanicilari bellege alir.
     *
     * Veritabani bos olsa da dolu olsa da environment variable
     * ile verilen Super Admin kullanicisinin mevcut oldugundan
     * emin olunur.
     */
    private void loadFromStore() {

        List<Map<String, Object>> saved =
                store.find(USERS_COLLECTION, null);

        for (Map<String, Object> row : saved) {

            User u = User.fromStorageMap(row);

            usersByEmail.put(u.email(), u);
            usersById.put(u.id(), u);
        }

        System.out.println(
                "[auth] " + saved.size()
                        + " kullanici depodan yuklendi"
        );

        /*
         * Veritabani bos olsa da dolu olsa da
         * configured Super Admin'i kontrol et.
         */
        ensureConfiguredSuperAdmin();
    }

    /**
     * Environment variable'lardan gelen kullaniciyi
     * Super Admin olarak olusturur veya gunceller.
     *
     * AWS / Docker tarafinda:
     *
     * MIDDLEWARE_USER=arslanbejna@gmail.com
     * MIDDLEWARE_PASSWORD=GERCEK_SIFRE
     *
     * tanimli olmalidir.
     */
    private void ensureConfiguredSuperAdmin() {

        String email =
                System.getenv("MIDDLEWARE_USER");

        String password =
                System.getenv("MIDDLEWARE_PASSWORD");

        // Kullanici adi tanimli degilse devam etme.
        if (email == null || email.isBlank()) {

            System.out.println(
                    "[auth] MIDDLEWARE_USER tanimli degil. "
                            + "Super Admin olusturulamadi."
            );

            return;
        }

        // Sifre tanimli degilse devam etme.
        if (password == null || password.isBlank()) {

            System.out.println(
                    "[auth] MIDDLEWARE_PASSWORD tanimli degil. "
                            + "Super Admin olusturulamadi."
            );

            return;
        }

        email = email.trim();

        User existing =
                usersByEmail.get(email);

        // ========================================================
        // KULLANICI YOKSA OLUSTUR
        // ========================================================

        if (existing == null) {

            User admin =
                    new User(
                            "user-" + UUID.randomUUID(),
                            "Super Admin",
                            email,
                            password,
                            "superAdmin",
                            Set.of(
                                    "Sensor",
                                    "Signal",
                                    "Acoustic",
                                    "Sonar",
                                    "Test"
                            ),
                            Set.of()
                    );

            persist(admin);

            System.out.println(
                    "[auth] Super Admin olusturuldu: "
                            + email
            );

            return;
        }

        // ========================================================
        // KULLANICI VARSA SUPER ADMIN OLARAK GUNCELLE
        // ========================================================

        existing.setPassword(password);

        existing.setRole("superAdmin");

        existing.setDepartments(
                Set.of(
                        "Sensor",
                        "Signal",
                        "Acoustic",
                        "Sonar",
                        "Test"
                )
        );

        existing.setPermissions(
                Set.of()
        );

        existing.setActive(true);
        existing.setDeleted(false);

        persist(existing);

        System.out.println(
                "[auth] Super Admin guncellendi: "
                        + email
        );
    }

    /**
     * Kullaniciyi hem bellege hem de depoya yazar.
     */
    private void persist(User u) {

        usersByEmail.put(
                u.email(),
                u
        );

        usersById.put(
                u.id(),
                u
        );

        List<Map<String, Object>> existing =
                store.find(
                        USERS_COLLECTION,
                        Map.of(
                                "userId",
                                u.id()
                        )
                );

        if (existing.isEmpty()) {

            store.insert(
                    USERS_COLLECTION,
                    u.toStorageMap()
            );

        } else {

            store.updateById(
                    USERS_COLLECTION,
                    String.valueOf(
                            existing
                                    .get(0)
                                    .get("id")
                    ),
                    u.toStorageMap()
            );
        }
    }

    /**
     * Degisen kullaniciyi depoya yazar.
     */
    public void save(User u) {
        persist(u);
    }

    // ============================================================
    //  KIMLIK DOGRULAMA
    // ============================================================

    /**
     * Ister_0002:
     * Kullanici adi (tam e-posta) ve sifre ile dogrulama.
     *
     * Pasif veya silinmis kullanici giris yapamaz.
     */
    public User authenticate(
            String username,
            String password
    ) {

        if (username == null
                || password == null) {

            return null;
        }

        User u =
                usersByEmail.get(
                        username.trim()
                );

        if (u == null
                || !u.canLogin()
                || !u.passwordMatches(password)) {

            return null;
        }

        return u;
    }

    // ============================================================
    //  OKUMA
    // ============================================================

    public User byEmail(String email) {

        if (email == null) {
            return null;
        }

        return usersByEmail.get(
                email.trim()
        );
    }

    public User byId(String id) {
        return usersById.get(id);
    }

    /**
     * Silinmemis kullanicilar.
     */
    public List<User> activeUsers() {

        List<User> list =
                new ArrayList<>();

        for (User u : usersById.values()) {

            if (!u.deleted()) {
                list.add(u);
            }
        }

        return list;
    }

    /**
     * Silinmisler dahil tum kullanicilar.
     */
    public List<User> allUsers() {

        return new ArrayList<>(
                usersById.values()
        );
    }

    /**
     * Aktif kullanici sayisi.
     */
    public int activeUserCount() {

        return (int)
                usersById
                        .values()
                        .stream()
                        .filter(
                                u ->
                                        u.active()
                                                && !u.deleted()
                        )
                        .count();
    }

    // ============================================================
    //  YONETIM (Ister_0004, Ister_0005)
    // ============================================================

    /**
     * Ister_0005:
     * Yeni kullanici ekler.
     *
     * E-posta zaten varsa false dondurur.
     */
    public boolean createUser(User u) {

        if (usersByEmail.containsKey(
                u.email()
        )) {

            return false;
        }

        persist(u);

        return true;
    }

    /**
     * Yeni kullanici icin benzersiz kimlik uretir.
     */
    public String newUserId() {

        return "user-"
                + UUID.randomUUID();
    }

    /**
     * Ad, rol, departman, yetki ve aktiflik
     * alanlarini gunceller.
     */
    public User updateUser(
            String id,
            String name,
            String role,
            Set<String> departments,
            Set<String> permissions,
            Boolean isActive
    ) {

        User u =
                usersById.get(id);

        if (u == null) {
            return null;
        }

        u.setName(name);
        u.setRole(role);
        u.setDepartments(departments);
        u.setPermissions(permissions);

        if (isActive != null) {
            u.setActive(isActive);
        }

        persist(u);

        return u;
    }

    /**
     * Ister_0004:
     * Yalnizca departman ve yetkileri gunceller.
     */
    public User updatePermissions(
            String id,
            Set<String> departments,
            Set<String> permissions
    ) {

        User u =
                usersById.get(id);

        if (u == null) {
            return null;
        }

        u.setDepartments(departments);
        u.setPermissions(permissions);

        persist(u);

        return u;
    }

    /**
     * Yumusak silme:
     * Kullanici listede gorunmez ancak geri alinabilir.
     */
    public User softDelete(String id) {

        User u =
                usersById.get(id);

        if (u == null) {
            return null;
        }

        u.setDeleted(true);

        persist(u);

        return u;
    }

    /**
     * Silinmis kullaniciyi geri getirir.
     */
    public User restore(String id) {

        User u =
                usersById.get(id);

        if (u == null) {
            return null;
        }

        u.setDeleted(false);

        persist(u);

        return u;
    }

    /**
     * Kalici silme:
     * Kullanici tamamen kaldirilir.
     */
    public boolean hardDelete(String id) {

        User u =
                usersById.remove(id);

        if (u == null) {
            return false;
        }

        usersByEmail.remove(
                u.email()
        );

        List<Map<String, Object>> existing =
                store.find(
                        USERS_COLLECTION,
                        Map.of(
                                "userId",
                                id
                        )
                );

        if (!existing.isEmpty()) {

            store.deleteById(
                    USERS_COLLECTION,
                    String.valueOf(
                            existing
                                    .get(0)
                                    .get("id")
                    )
            );
        }

        return true;
    }

    /**
     * Sifre sifirlama.
     *
     * Yeni sifre verilmezse rastgele sifre uretilir.
     */
    public String resetPassword(
            String id,
            String newPassword
    ) {

        User u =
                usersById.get(id);

        if (u == null) {
            return null;
        }

        String password =
                (newPassword == null
                        || newPassword.isBlank())

                        ? generatePassword()

                        : newPassword;

        u.setPassword(password);

        persist(u);

        return password;
    }

    /**
     * Rastgele sifre uretir.
     */
    private static String generatePassword() {

        String alphabet =
                "abcdefghijkmnpqrstuvwxyz"
                        + "ABCDEFGHJKLMNPQRSTUVWXYZ"
                        + "23456789";

        java.security.SecureRandom random =
                new java.security.SecureRandom();

        StringBuilder sb =
                new StringBuilder();

        for (int i = 0; i < 12; i++) {

            sb.append(
                    alphabet.charAt(
                            random.nextInt(
                                    alphabet.length()
                            )
                    )
            );
        }

        return sb.toString();
    }
}