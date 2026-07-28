package middleware.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import middleware.events.EventBus;
import middleware.protocol.Router;

public class TcpServer {

    private final int port;
    private final Router router;
    private final EventBus eventBus;
    private static final int MAX_CLIENT = 16;

    public TcpServer(int port, Router router, EventBus eventBus) {
        this.port = port;
        this.router = router;
        this.eventBus = eventBus;
    }

    public void start() throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENT);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("==============================================");
            System.out.println(" DBMS Middleware - TCP Server (Observer)");
            System.out.println(" Port          : " + port);
            System.out.println(" Max clients   : " + MAX_CLIENT);
            System.out.println(" Protocol      : line-based JSON (ending with \\n)");
            System.out.println(" Observer      : subscribe/unsubscribe + push");
            System.out.println("==============================================");

            while (true) {
                Socket client = serverSocket.accept();
                pool.submit(new ClientHandler(client, router, eventBus));
            }
        }
    }
}
