package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Elle deneme istemcisi (tek protokol, PROTOKOL.md).
 *
 *   java -cp "out;lib/*" middleware.TestClient [host] [port]
 */
public class TestClient {

    private static final String USER = "ayse@company.com";
    private static final String PASS = "Ddsfkln1as1sFd";

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5150;

        try (Socket socket = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            System.out.println("Connected to " + host + ":" + port + "\n");

            String cred = "\"username\":\"" + USER + "\",\"password\":\"" + PASS + "\"";

            send(out, in, "{\"requestId\":\"1\",\"action\":\"PING\"," + cred + "}");
            send(out, in, "{\"requestId\":\"2\",\"action\":\"LOGIN\"," + cred + "}");
            send(out, in, "{\"requestId\":\"3\",\"action\":\"CREATE_DATABASE\"," + cred
                    + ",\"database\":\"deneme\",\"document\":{\"department\":\"Test\",\"description\":\"elle deneme\"}}");
            send(out, in, "{\"requestId\":\"4\",\"action\":\"CREATE_COLLECTION\"," + cred
                    + ",\"database\":\"deneme\",\"collection\":\"kayitlar\"}");
            send(out, in, "{\"requestId\":\"5\",\"action\":\"WRITE\"," + cred
                    + ",\"database\":\"deneme\",\"collection\":\"kayitlar\",\"document\":{\"mesaj\":\"selam\"}}");
            send(out, in, "{\"requestId\":\"6\",\"action\":\"READ\"," + cred
                    + ",\"database\":\"deneme\",\"collection\":\"kayitlar\"}");
            send(out, in, "{\"requestId\":\"7\",\"action\":\"LIST_DATABASES\"," + cred + "}");
            send(out, in, "{\"requestId\":\"8\",\"action\":\"STATS\"," + cred + "}");
            send(out, in, "bozuk json {{{");
        }
        System.out.println("\nBitti.");
    }

    private static void send(PrintWriter out, BufferedReader in, String request) throws Exception {
        System.out.println(">> " + request);
        out.println(request);
        System.out.println("<< " + in.readLine() + "\n");
    }
}
