package middleware;

import middleware.auth.AuthService;
import middleware.events.ConsoleLogObserver;
import middleware.events.EventBus;
import middleware.protocol.Router;
import middleware.server.TcpServer;
import middleware.storage.InMemoryStore;
import middleware.storage.MongoStore;
import middleware.storage.Store;

/**
 * Giris noktasi.
 *
 *   TcpServer -> Router (tek protokol, PROTOKOL.md) -> Store
 *                                                      |- InMemoryStore
 *                                                      |- MongoStore
 *
 * Yapilandirma ortam degiskenlerinden okunur; koda gomulu degildir:
 *   PORT      -> dinlenecek port (varsayilan 5150)
 *   MONGO_URI -> MongoDB adresi; verilmezse bellek deposu kullanilir
 *   MONGO_DB  -> varsayilan veritabani adi (varsayilan "dbms")
 */
public class Main {

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);
        Store store = createStore();

        EventBus eventBus = new EventBus();
        eventBus.register(new ConsoleLogObserver());

        AuthService auth = new AuthService();
        Router router = new Router(store, eventBus, auth);

        // Sunucu kapanirken veritabani baglantisini duzgunce kapat.
        Runtime.getRuntime().addShutdownHook(new Thread(store::close));

        new TcpServer(port, router, eventBus).start();
    }

    /** Oncelik: komut satiri argumani > PORT ortam degiskeni > varsayilan. */
    private static int resolvePort(String[] args) {
        int port = 5150; // PROTOKOL.md varsayilan portu
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        return port;
    }

    /**
     * MONGO_URI tanimliysa MongoDB'ye baglanmayi dener.
     * Baglanti kurulamazsa sunucu CALISMAYA DEVAM EDER ama bellek deposuna
     * duser ve bunu acikca uyarir — sessizce veri kaybetmektense gorunur
     * bir uyari vermek yeglenir.
     */
    private static Store createStore() {
        String uri = System.getenv("MONGO_URI");

        if (uri == null || uri.isBlank()) {
            System.out.println("[store] MONGO_URI tanimli degil -> bellek deposu (veriler kalici DEGIL)");
            Store store = new InMemoryStore();
            store.loadSampleData();
            return store;
        }

        String dbName = System.getenv("MONGO_DB");
        if (dbName == null || dbName.isBlank()) dbName = "dbms";

        try {
            MongoStore mongo = new MongoStore(uri, dbName);
            if (mongo.isHealthy()) {
                System.out.println("[store] MongoDB baglantisi kuruldu: " + uri + " (db: " + dbName + ")");
                return mongo;
            }
            mongo.close();
            System.out.println("[store] UYARI: MongoDB yanit vermiyor: " + uri);
        } catch (Exception e) {
            System.out.println("[store] UYARI: MongoDB baglantisi kurulamadi: " + e.getMessage());
        }

        System.out.println("[store] UYARI: bellek deposuna dusuldu - VERILER KALICI DEGIL!");
        Store store = new InMemoryStore();
        store.loadSampleData();
        return store;
    }
}
