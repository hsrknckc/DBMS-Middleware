package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ObserverClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        String collection = args.length > 2 ? args[2] : "users";

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("{\"action\":\"subscribe\",\"collection\":\"" + collection + "\"}");
            System.out.println("Subscription response: " + in.readLine());
            System.out.println("'" + collection + "' is being observed... (Ctrl+C to quit)\n");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[EVENT RECEIVED] " + line);
            }
        }

        System.out.println("Server closed the connection.");
    }
}
