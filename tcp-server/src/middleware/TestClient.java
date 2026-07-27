package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            System.out.println("Connected to server: " + host + ":" + port + "\n");

            send(out, in, "{\"requestId\":\"1\",\"action\":\"ping\",\"payload\":{}}");

            send(out, in, "{\"requestId\":\"2\",\"action\":\"auth.login\",\"payload\":"
                    + "{\"email\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}}");

            // Yukaridaki cevaptaki token'i elle kopyalamak yerine gercek akista
            // frontend token'i saklar. Burada sadece bicimi gostermek amacli.
            System.out.println("(For the rest, run AuthProtocolTest — it manages the token flow automatically.)");
        }
        System.out.println("\nTest finished, connection closed.");
    }

    private static void send(PrintWriter out, BufferedReader in, String request) throws Exception {
        System.out.println(">> " + request);
        out.println(request);
        System.out.println("<< " + in.readLine() + "\n");
    }
}
