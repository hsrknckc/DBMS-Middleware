package middleware.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import middleware.events.EventBus;
import middleware.protocol.Router;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final Router router;
    private final EventBus eventBus;

    public ClientHandler(Socket socket, Router router, EventBus eventBus) {
        this.socket = socket;
        this.router = router;
        this.eventBus = eventBus;
    }

    @Override
    public void run() {
        String clientInfo = socket.getInetAddress() + ":" + socket.getPort();
        System.out.println("[+] connected: " + clientInfo);

        ClientSession session = null;

        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            session = new ClientSession(out, clientInfo);
            eventBus.register(session);

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                System.out.println("[>] " + clientInfo + " request : " + line);
                String response = router.handle(line, session);
                System.out.println("[<] " + clientInfo + " response : " + response);

                session.send(response);
            }

        } catch (IOException e) {
            System.out.println("[!] " + clientInfo + " connection error: " + e.getMessage());
        } finally {
            if (session != null) {
                eventBus.unregister(session);
            }
        }

        System.out.println("[-] disconnected: " + clientInfo);
    }
}
