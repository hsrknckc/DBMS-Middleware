package middleware;

import middleware.auth.AuthService;
import middleware.events.ConsoleLogObserver;
import middleware.events.EventBus;
import middleware.protocol.Router;
import middleware.server.TcpServer;
import middleware.storage.DataStore;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);

        DataStore store = new DataStore();
        store.loadSampleData();

        EventBus eventBus = new EventBus();
        eventBus.register(new ConsoleLogObserver());

        AuthService auth = new AuthService();

        Router router = new Router(store, eventBus, auth);

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
}
