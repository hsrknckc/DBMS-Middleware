package middleware;

import middleware.auth.AuthService;
import middleware.events.ConsoleLogObserver;
import middleware.events.EventBus;
import middleware.protocol.RequestRouter;
import middleware.protocol.BackendRouter;
import middleware.protocol.ProtocolDispatcher;
import middleware.server.TcpServer;
import middleware.storage.DataStore;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = 5150;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort.trim());
        }
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        DataStore store = new DataStore();
        store.loadSampleData();

        EventBus eventBus = new EventBus();
        eventBus.register(new ConsoleLogObserver());

        AuthService auth = new AuthService();

        RequestRouter frontendRouter = new RequestRouter(store, eventBus, auth);
        BackendRouter backendRouter = new BackendRouter(store, eventBus, auth);
        ProtocolDispatcher dispatcher = new ProtocolDispatcher(frontendRouter, backendRouter);

        new TcpServer(port, dispatcher, eventBus).start();
    }
}
