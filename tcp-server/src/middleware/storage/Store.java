package middleware.storage;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Veri deposu sozlesmesi.
 *
 * Ust katman (Router) yalnizca bu arayuzu tanir; verinin bellekte mi
 * MongoDB'de mi durdugunu BILMEZ. Boylece depolama degistiginde protokol
 * ve yetki katmanina dokunulmaz.
 *
 * Iki uygulamasi vardir:
 *   InMemoryStore -> bellekte tutar (MongoDB yokken, gecici)
 *   MongoStore    -> gercek MongoDB (Ister_0016 okuma, Ister_0017 yazma)
 *
 * KAYIT MODELI
 * Her kayit bir Map'tir (JSON object karsiligi) ve su alanlari icerir:
 *   "id"        -> benzersiz kimlik
 *   "createdAt" -> olusturma zamani (ISO metin)
 *   "updatedAt" -> son degisiklik zamani (ISO metin)
 * artı kullanicinin kendi alanlari.
 *
 * KOLEKSIYON ADLANDIRMASI
 * Koleksiyon adi "veritabani/koleksiyon" bicimindedir. key() yardimcisi
 * bu adi uretir.
 */
public interface Store {

    /** Yeni kayit ekler; id ve zaman damgalarini atar, kaydin tam halini doner. */
    Map<String, Object> insert(String collection, Map<String, Object> data);

    /** Birden fazla kaydi ekler, atanan id'leri doner. */
    List<String> insertMany(String collection, List<Map<String, Object>> records);

    /**
     * Kayitlari oldugu gibi yazar (restore).
     * id / createdAt / updatedAt varsa korunur; yoksa uretilir.
     * Ayni id zaten varsa IllegalStateException firlatir.
     */
    List<String> insertExact(String collection, List<Map<String, Object>> records);

    /** Filtreye uyan kayitlari doner. filter null/bos ise tum kayitlar. */
    List<Map<String, Object>> find(String collection, Map<String, Object> filter);

    /**
     * Filtreye uyan kayitlari bellege toplmadan sirayla isler (OOM'suz akis).
     * Varsayilan uygulama find() sonucunu gezer; MongoStore cursor ile ezer.
     */
    default void forEach(String collection, Map<String, Object> filter,
                         Consumer<Map<String, Object>> consumer) {
        for (Map<String, Object> record : find(collection, filter)) {
            consumer.accept(record);
        }
    }

    /** id ile tek kaydi kismen gunceller; guncel halini doner, kayit yoksa null. */
    Map<String, Object> updateById(String collection, String id, Map<String, Object> data);

    /** id ile tek kaydi siler; silindiyse true. */
    boolean deleteById(String collection, String id);

    /** Bos koleksiyon olusturur. Zaten varsa false. */
    boolean createCollection(String database, String collection);

    /** Koleksiyonu ve icindeki kayitlari siler. */
    boolean dropCollection(String database, String collection);

    /** Bir veritabanindaki koleksiyon adlarini doner. */
    List<String> listCollections(String database);

    /** Var olan veritabani adlarini doner. */
    List<String> listDatabases();

    /** Veritabanini ve tum koleksiyonlarini siler; silinen koleksiyon sayisini doner. */
    int dropDatabase(String database);

    /** "db/col" -> kayit sayisi esleme (ozet bilgiler icin). */
    Map<String, Object> collectionInfo();

    /**
     * Depolama katmani erisilebilir mi? (Ister_0018)
     * Bellekte her zaman true; MongoDB'de gercek baglanti kontrolu yapilir.
     */
    boolean isHealthy();

    /** Baglantiyi kapatir. Bellekte bir sey yapmaz. */
    default void close() { }

    /** Demo/test icin ornek kayitlar yukler. */
    default void loadSampleData() { }

    /** "db/col" anahtari uretir; database bos ise koleksiyon adi tek basina kullanilir. */
    static String key(String database, String collection) {
        return (database == null || database.isBlank()) ? collection : database + "/" + collection;
    }
}
