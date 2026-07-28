package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Observer (canli guncelleme) izleyicisi.
 *
 * Bir koleksiyona abone olur ve o koleksiyonda degisiklik oldukca
 * sunucunun gonderdigi push mesajlarini ekrana basar.
 *
 *   java -cp "out;lib/*" middleware.ObserverClient [database] [collection]
 *
 * Baska bir terminalde TestClient calistirip degisiklikleri gozleyin.
 */
public class ObserverClient {

    private static final String USER = "ayse@company.com";
    private static final String PASS = "Ddsfkln1as1sFd";

    public static void main(String[] args) throws Exception {
        String database = args.length > 0 ? args[0] : "deneme";
        String collection = args.length > 1 ? args[1] : "kayitlar";

        try (Socket socket = new Socket("localhost", 5150);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            String req = "{\"requestId\":\"sub-1\",\"action\":\"SUBSCRIBE\","
                    + "\"username\":\"" + USER + "\",\"password\":\"" + PASS + "\","
                    + "\"database\":\"" + database + "\",\"collection\":\"" + collection + "\"}";
            out.println(req);
            System.out.println("Abone olundu: " + database + "/" + collection);
            System.out.println("Cevap: " + in.readLine());
            System.out.println("\nDegisiklikler bekleniyor (Ctrl+C ile cikin)...\n");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[PUSH] " + line);
            }
        }
    }
}
