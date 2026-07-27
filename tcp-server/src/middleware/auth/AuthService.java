package middleware.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kimlik dogrulama ve yetkilendirme servisi.
 *
 * - Kullanici kayitlarini tutar (baslangicta iki ornek kullanici).
 * - login: email + sifre dogrulanirsa bir token uretir ve token -> kullanici
 *   eslesmesini saklar. Sonraki her istek bu token'i tasir.
 * - resolve: gelen token'dan kullaniciyi bulur (yoksa null).
 *
 * Ister_0002 (giris kontrolu), Ister_0003 (super admin), Ister_0004/0005
 * (yetki ve kullanici yonetimi) bu servis uzerinden karsilanir.
 */
public class AuthService {

    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> sessionsByToken = new ConcurrentHashMap<>();

    public AuthService() {
        seedUsers();
    }

    /** Baslangic kullanicilari: bir super admin, bir sinirli kullanici. */
    private void seedUsers() {
        User admin = new User(
                "user-1", "Ayse Yilmaz", "ayse@company.com", "Ddsfkln1as1sFd",
                "superAdmin",
                Set.of("Sensor", "Signal", "Acoustic", "Sonar", "Test"),
                Set.of()); // super admin icin yetki listesi onemsiz, hepsine sahip
        User normal = new User(
                "user-2", "Mehmet Kaya", "mehmet@company.com", "3QPKdvlca34avSl",
                "user",
                Set.of("Sensor"),
                Set.of("databaseView", "dataView", "dataCreate")); // sinirli yetki
        register(admin);
        register(normal);
    }

    private void register(User u) {
        usersByEmail.put(u.email(), u);
        usersById.put(u.id(), u);
    }

    /**
     * Giris dogrulamasi. Basariliysa token dondurur, degilse null.
     * (Ister_0002: kullanici girisinin dogru yapildigini kontrol eder.)
     */
    public String login(String email, String password) {
        User u = usersByEmail.get(email);
        if (u == null || !u.active() || !u.passwordMatches(password)) {
            return null;
        }
        String token = "tok-" + UUID.randomUUID();
        sessionsByToken.put(token, u);
        return token;
    }

    /** Token'i gecersiz kilar (logout). */
    public void logout(String token) {
        if (token != null) sessionsByToken.remove(token);
    }

    /** Token'dan kullaniciyi cozer; gecersizse null. */
    public User resolve(String token) {
        if (token == null) return null;
        return sessionsByToken.get(token);
    }

    /**
     * Backend protokolu her istekte username+password tasidigi icin
     * token'siz, dogrudan kimlik dogrulama. username, email ya da
     * kullanici adi olabilir; ikisini de deniyoruz.
     * Basarisizsa null doner.
     */
    public User authenticate(String username, String password) {
        if (username == null || password == null) return null;
        User u = usersByEmail.get(username);
        if (u == null) {
            // email degilse, isimle eslesmeyi dene
            for (User cand : usersById.values()) {
                if (username.equals(cand.email())) { u = cand; break; }
            }
        }
        if (u == null || !u.active() || !u.passwordMatches(password)) return null;
        return u;
    }

    public User byEmail(String email) { return usersByEmail.get(email); }
    public User byId(String id) { return usersById.get(id); }
    public List<User> allUsers() { return List.copyOf(usersById.values()); }

    /** Yeni kullanici ekler (Ister_0005). Email zaten varsa false doner. */
    public boolean createUser(User u) {
        if (usersByEmail.containsKey(u.email())) return false;
        register(u);
        return true;
    }

    public int activeUserCount() {
        return (int) usersById.values().stream().filter(User::active).count();
    }
}
