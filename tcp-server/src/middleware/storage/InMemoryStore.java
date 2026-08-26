package middleware.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Bellek ici (in-memory) veri deposu.
 *
 * MongoDB yokken kullanilir. Veriler surec belleginde durur; sunucu
 * yeniden baslayinca SIFIRLANIR.
 *
 * Thread guvenligi: sunucu her istemciyi ayri thread'de calistirdigi icin
 * ConcurrentHashMap + synchronized bloklar kullanilir.
 */
public class InMemoryStore implements Store {

    private final Map<String, List<Map<String, Object>>> collections = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    private static String currentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Override
    public Map<String, Object> insert(String collection, Map<String, Object> data) {
        String id = "rec-" + idCounter.getAndIncrement();
        Map<String, Object> record = new LinkedHashMap<>(data);
        record.put("id", id);
        String t = currentTimestamp();
        record.put("createdAt", t);
        record.put("updatedAt", t);

        List<Map<String, Object>> list =
                collections.computeIfAbsent(collection, k -> new ArrayList<>());
        synchronized (list) {
            list.add(record);
        }
        return new LinkedHashMap<>(record);
    }

    @Override
    public Map<String, Object> updateById(String collection, String id, Map<String, Object> data) {
        List<Map<String, Object>> list = collections.get(collection);
        if (list == null) return null;
        synchronized (list) {
            for (Map<String, Object> record : list) {
                if (id.equals(record.get("id"))) {
                    Object created = record.get("createdAt");
                    record.putAll(data);
                    record.put("id", id);
                    if (created != null) {
                        record.put("createdAt", created); // createdAt ezilmesini önle
                    }
                    record.put("updatedAt", currentTimestamp());
                    return new LinkedHashMap<>(record);
                }
            }
        }
        return null;
    }

    @Override
    public List<String> insertMany(String collection, List<Map<String, Object>> records) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> data : records) {
            ids.add((String) insert(collection, data).get("id"));
        }
        return ids;
    }

    @Override
    public List<Map<String, Object>> find(String collection, Map<String, Object> filter) {
        List<Map<String, Object>> list = collections.get(collection);
        List<Map<String, Object>> result = new ArrayList<>();
        if (list == null) return result;

        synchronized (list) {
            for (Map<String, Object> record : list) {
                if (matches(record, filter)) {
                    result.add(new LinkedHashMap<>(record));
                }
            }
        }
        return result;
    }

    @Override
    public boolean deleteById(String collection, String id) {
        List<Map<String, Object>> list = collections.get(collection);
        if (list == null) return false;
        synchronized (list) {
            return list.removeIf(k -> id.equals(k.get("id")));
        }
    }


    // ============================================================
    //  KOLEKSIYON VE VERITABANI YONETIMI
    //  Anahtar bicimi: "veritabani/koleksiyon"
    // ============================================================

    /** Bos koleksiyon olusturur. Zaten varsa false doner. */
    @Override
    public boolean createCollection(String database, String collection) {
        String key = Store.key(database, collection);
        if (collections.containsKey(key)) return false;
        collections.put(key, new ArrayList<>());
        return true;
    }

    /** Koleksiyonu ve icindeki tum kayitlari siler. */
    @Override
    public boolean dropCollection(String database, String collection) {
        return collections.remove(Store.key(database, collection)) != null;
    }

    /** Bir veritabanindaki koleksiyon adlarini doner (veritabani oneki ayiklanmis). */
    @Override
    public List<String> listCollections(String database) {
        String prefix = database + "/";
        List<String> result = new ArrayList<>();
        for (String key : collections.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(key.substring(prefix.length()));
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    /** Kayit tasiyan ya da olusturulmus tum veritabani adlarini doner. */
    @Override
    public List<String> listDatabases() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String key : collections.keySet()) {
            int slash = key.indexOf('/');
            if (slash > 0) names.add(key.substring(0, slash));
        }
        List<String> result = new ArrayList<>(names);
        java.util.Collections.sort(result);
        return result;
    }

    /** Veritabanini ve altindaki TUM koleksiyonlari siler. Silinen koleksiyon sayisini doner. */
    @Override
    public int dropDatabase(String database) {
        String prefix = database + "/";
        int count = 0;
        for (String key : new ArrayList<>(collections.keySet())) {
            if (key.startsWith(prefix)) {
                collections.remove(key);
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean isHealthy() {
        return true; // bellek her zaman erisilebilir
    }

    @Override
    public Map<String, Object> collectionInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : collections.entrySet()) {
            synchronized (e.getValue()) {
                info.put(e.getKey(), (long) e.getValue().size());
            }
        }
        return info;
    }

    private boolean matches(Map<String, Object> record, Map<String, Object> filter) {
        return FilterMatcher.matches(record, filter);
    }

    @Override
    public void loadSampleData() {
        Map<String, Object> u1 = new LinkedHashMap<>();
        u1.put("name", "Mehmet Kaya");
        u1.put("email", "mehmet.kaya@company.com");
        u1.put("department", "Sensor");
        insert("demo/users", u1);

        Map<String, Object> u2 = new LinkedHashMap<>();
        u2.put("name", "Zeynep Demir");
        u2.put("email", "zeynep.demir@company.com");
        u2.put("department", "Acoustic");
        insert("demo/users", u2);
    }
}
