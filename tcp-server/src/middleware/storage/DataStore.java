package middleware.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DataStore {

    private final Map<String, List<Map<String, Object>>> collections = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    private static String currentTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

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

    public List<String> insertMany(String collection, List<Map<String, Object>> records) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> data : records) {
            ids.add((String) insert(collection, data).get("id"));
        }
        return ids;
    }

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

    public Map<String, Object> updateById(String collection, String id, Map<String, Object> data) {
        List<Map<String, Object>> list = collections.get(collection);
        if (list == null) return null;
        synchronized (list) {
            for (Map<String, Object> record : list) {
                if (id.equals(record.get("id"))) {
                    record.putAll(data);         
                    record.put("id", id);        
                    record.put("updatedAt", currentTimestamp()); 
                    return new LinkedHashMap<>(record); 
                }
            }
        }
        return null;
    }

    public boolean deleteById(String collection, String id) {
        List<Map<String, Object>> list = collections.get(collection);
        if (list == null) return false;
        synchronized (list) {
            return list.removeIf(k -> id.equals(k.get("id")));
        }
    }

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
        if (filter == null || filter.isEmpty()) return true;
        for (Map.Entry<String, Object> f : filter.entrySet()) {
            Object value = record.get(f.getKey());
            if (value == null || !value.equals(f.getValue())) return false;
        }
        return true;
    }

    public void loadSampleData() {
        Map<String, Object> u1 = new LinkedHashMap<>();
        u1.put("name", "Mehmet Kaya");
        u1.put("email", "mehmet.kaya@company.com");
        u1.put("department", "Sensor");
        insert("users", u1);

        Map<String, Object> u2 = new LinkedHashMap<>();
        u2.put("name", "Zeynep Demir");
        u2.put("email", "zeynep.demir@company.com");
        u2.put("department", "Acoustic");
        insert("users", u2);
    }
}
