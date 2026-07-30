package middleware;

import middleware.audit.AuditService;
import middleware.auth.AuthService;
import middleware.config.AppConfig;
import middleware.events.ConsoleLogObserver;
import middleware.events.Event;
import middleware.events.EventBus;
import middleware.file.RequestFileService;
import middleware.protocol.Router;
import middleware.server.TcpServer;
import middleware.storage.InMemoryStore;
import middleware.storage.MongoStore;
import middleware.storage.Store;

public class Main {

    /** Sunucu durumu olaylarinin yayinlandigi sanal koleksiyon. */
    private static final String SERVER_STATUS_TOPIC = "__server__/status";

    public static void main(String[] args) throws Exception {
        AppConfig config = new AppConfig();
        if (config.loadedFrom() != null) {
            System.out.println("[config] Yapilandirma okundu: " + config.loadedFrom());
        }

        int port = resolvePort(args, config);

        EventBus eventBus = new EventBus();
        eventBus.register(new ConsoleLogObserver());

        Store store = createStore(config, eventBus);

        RequestFileService files = new RequestFileService(config.requestDirectory());
        files.ensureBaseDirectory();
        System.out.println("[file] Talep klasoru: " + files.baseDirectory());

        AuthService auth = new AuthService(store);
        AuditService audit = new AuditService(store);
        Router router = new Router(store, eventBus, auth, files, audit, config.autoSchema());

        // Sunucu kapanirken veritabani baglantisini duzgunce kapat.
        Runtime.getRuntime().addShutdownHook(new Thread(store::close));

        new TcpServer(port, router, eventBus).start();
    }

    /** Komut satiri argumani her seyi ezer; yoksa yapilandirmaya bakilir. */
    private static int resolvePort(String[] args, AppConfig config) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                System.out.println("[config] UYARI: gecersiz port argumani '" + args[0] + "'");
            }
        }
        return config.serverPort(5150); // PROTOKOL.md varsayilan portu
    }

    /**
     * MongoDB adresi cozulebiliyorsa baglanmayi dener.
     * Baglanti kurulamazsa sunucu CALISMAYA DEVAM EDER ama bellek deposuna
     * duser ve bunu acikca uyarir — sessizce veri kaybetmektense gorunur
     * bir uyari vermek yeglenir.
     *
     * MongoDB kullanildiginda sunucu erisilebilirligi Observer deseni ile
     * izlenir; durum degistiginde abonelere olay gonderilir.
     */
    private static Store createStore(AppConfig config, EventBus eventBus) {
        String uri = config.mongoUri();

        if (uri == null || uri.isBlank()) {
            System.out.println("[store] MongoDB adresi tanimli degil -> bellek deposu (veriler kalici DEGIL)");
            return inMemory();
        }

        String dbName = config.mongoDatabase();

        try {
            // Observer: heartbeat durumu degistikce hem loga yazilir hem
            // abone istemcilere push edilir.
            MongoStore mongo = new MongoStore(uri, dbName, available -> {
                System.out.println("[store] MongoDB durumu: "
                        + (available ? "ERISILEBILIR" : "ERISILEMIYOR"));
                eventBus.publish(new Event(
                        available ? "server_up" : "server_down",
                        SERVER_STATUS_TOPIC,
                        java.util.Map.of("available", available)));
            });

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
        return inMemory();
    }

    private static Store inMemory() {
        Store store = new InMemoryStore();
        store.loadSampleData();
        return store;
    }
}
