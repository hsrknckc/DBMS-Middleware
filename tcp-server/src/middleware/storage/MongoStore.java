package middleware.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.bson.Document;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;

/**
 * MongoDB veri deposu (Ister_0016 okuma, Ister_0017 yazma).
 *
 * ADLANDIRMA
 * Ust katman koleksiyonlari "veritabani/koleksiyon" bicimli tek string
 * olarak gonderir. Burada ikiye ayrilip MongoDB'nin dogal veritabani +
 * koleksiyon yapisina eslenir. "/" yoksa varsayilan veritabani kullanilir.
 *
 * KIMLIK YONETIMI
 * MongoDB her belgeye kendi "_id" alanini (ObjectId) ekler. ObjectId
 * JSON'a cevrilemedigi icin okumalarda projeksiyonla DISLANIR. Bunun
 * yerine kendi "id" alanimiz kullanilir: "rec-" + UUID. Bellek deposundan
 * farki, sayacin surece bagli olmamasi — sunucu yeniden baslasa bile
 * kimlikler cakismaz.
 *
 * TIP UYUMU
 * Yalnizca JSON'un dogal tipleri yazilir. Zaman damgalari Date olarak
 * degil ISO metin olarak saklanir; boylece okunan her belge dogrudan
 * Json.stringify'a verilebilir.
 */
public class MongoStore implements Store {

    /** MongoDB'nin kendi sistem veritabanlari — listelerde gosterilmez. */
    private static final List<String> SYSTEM_DATABASES = List.of("admin", "local", "config");

    private final MongoClient client;
    private final String defaultDatabase;

    /**
     * @param uri             baglanti adresi, orn. "mongodb://mongo:27017"
     * @param defaultDatabase koleksiyon adinda "/" yoksa kullanilacak veritabani
     */
    public MongoStore(String uri, String defaultDatabase) {
        this.client = MongoClients.create(uri);
        this.defaultDatabase = defaultDatabase;
    }

    // ------------------------------------------------------------
    //  Yardimcilar
    // ------------------------------------------------------------

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** "db/col" -> veritabani adi. */
    private String databaseOf(String name) {
        int slash = name.indexOf('/');
        return (slash >= 0) ? name.substring(0, slash) : defaultDatabase;
    }

    /** "db/col" -> koleksiyon adi. */
    private static String collectionOf(String name) {
        int slash = name.indexOf('/');
        return (slash >= 0) ? name.substring(slash + 1) : name;
    }

    private MongoCollection<Document> coll(String name) {
        return client.getDatabase(databaseOf(name)).getCollection(collectionOf(name));
    }

    /** Filtre Map'ini Mongo sorgusuna cevirir; bos/null ise tum belgeler. */
    private static Document query(Map<String, Object> filter) {
        return (filter == null || filter.isEmpty()) ? new Document() : new Document(filter);
    }

    /** Belgeyi duz Map'e cevirir (_id zaten projeksiyonla dislanmistir). */
    private static Map<String, Object> toMap(Document doc) {
        Map<String, Object> m = new LinkedHashMap<>(doc);
        m.remove("_id");
        return m;
    }

    // ------------------------------------------------------------
    //  Kayit islemleri
    // ------------------------------------------------------------

    @Override
    public Map<String, Object> insert(String collection, Map<String, Object> data) {
        String t = now();
        Document doc = new Document(data);
        doc.put("id", "rec-" + UUID.randomUUID());
        doc.put("createdAt", t);
        doc.put("updatedAt", t);

        coll(collection).insertOne(doc);
        return toMap(doc);   // insertOne belgeye _id ekler; toMap onu cikarir
    }

    @Override
    public List<String> insertMany(String collection, List<Map<String, Object>> records) {
        List<String> ids = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        String t = now();

        for (Map<String, Object> data : records) {
            String id = "rec-" + UUID.randomUUID();
            Document doc = new Document(data);
            doc.put("id", id);
            doc.put("createdAt", t);
            doc.put("updatedAt", t);
            docs.add(doc);
            ids.add(id);
        }
        if (!docs.isEmpty()) {
            coll(collection).insertMany(docs);
        }
        return ids;
    }

    @Override
    public List<Map<String, Object>> find(String collection, Map<String, Object> filter) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : coll(collection)
                .find(query(filter))
                .projection(Projections.excludeId())) {
            result.add(toMap(doc));
        }
        return result;
    }

    @Override
    public Map<String, Object> updateById(String collection, String id, Map<String, Object> data) {
        Document changes = new Document(data);
        changes.remove("id");         // kimlik degistirilemez
        changes.remove("createdAt");  // olusturma zamani degistirilemez
        changes.put("updatedAt", now());

        Document updated = coll(collection).findOneAndUpdate(
                Filters.eq("id", id),
                new Document("$set", changes),
                new FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.AFTER)
                        .projection(Projections.excludeId()));

        return (updated == null) ? null : toMap(updated);
    }

    @Override
    public boolean deleteById(String collection, String id) {
        return coll(collection).deleteOne(Filters.eq("id", id)).getDeletedCount() > 0;
    }

    // ------------------------------------------------------------
    //  Koleksiyon ve veritabani yonetimi
    // ------------------------------------------------------------

    @Override
    public boolean createCollection(String database, String collection) {
        MongoDatabase db = client.getDatabase(database);
        for (String existing : db.listCollectionNames()) {
            if (existing.equals(collection)) return false;
        }
        db.createCollection(collection);   // MongoDB bos koleksiyonu saklar
        return true;
    }

    @Override
    public boolean dropCollection(String database, String collection) {
        MongoDatabase db = client.getDatabase(database);
        boolean found = false;
        for (String existing : db.listCollectionNames()) {
            if (existing.equals(collection)) { found = true; break; }
        }
        if (!found) return false;
        db.getCollection(collection).drop();
        return true;
    }

    @Override
    public List<String> listCollections(String database) {
        List<String> names = new ArrayList<>();
        for (String name : client.getDatabase(database).listCollectionNames()) {
            names.add(name);
        }
        java.util.Collections.sort(names);
        return names;
    }

    @Override
    public List<String> listDatabases() {
        List<String> names = new ArrayList<>();
        for (String name : client.listDatabaseNames()) {
            if (!SYSTEM_DATABASES.contains(name)) names.add(name);
        }
        java.util.Collections.sort(names);
        return names;
    }

    @Override
    public int dropDatabase(String database) {
        MongoDatabase db = client.getDatabase(database);
        int count = 0;
        for (String ignored : db.listCollectionNames()) count++;
        db.drop();
        return count;
    }

    @Override
    public Map<String, Object> collectionInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        for (String dbName : listDatabases()) {
            MongoDatabase db = client.getDatabase(dbName);
            for (String colName : db.listCollectionNames()) {
                info.put(Store.key(dbName, colName), db.getCollection(colName).countDocuments());
            }
        }
        return info;
    }

    // ------------------------------------------------------------
    //  Saglik ve yasam dongusu
    // ------------------------------------------------------------

    @Override
    public boolean isHealthy() {
        try {
            client.getDatabase(defaultDatabase).runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
