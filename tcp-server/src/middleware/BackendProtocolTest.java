package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class BackendProtocolTest {

    static String host = "localhost";
    static int port = 5150;
    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);

        try (Socket s = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            System.out.println("--- PING (Ister_0018) ---");
            String ping = rpc(out, in, "{\"requestId\":\"1\",\"action\":\"PING\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}");
            check("PING status OK", ping.contains("\"status\":\"OK\""));
            check("requestId geri donuyor", ping.contains("\"requestId\":\"1\""));

            System.out.println("--- Yanlis kimlik -> UNAUTHORIZED ---");
            String bad = rpc(out, in, "{\"requestId\":\"2\",\"action\":\"READ\",\"username\":\"ayse@company.com\",\"password\":\"yanlis\",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("wrong password UNAUTHORIZED", bad.contains("\"status\":\"UNAUTHORIZED\""));

            System.out.println("--- WRITE (Ister_0017) ---");
            String w = rpc(out, in, "{\"requestId\":\"3\",\"action\":\"WRITE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Ali\",\"sinif\":3}}");
            check("WRITE status OK", w.contains("\"status\":\"OK\""));
            check("WRITE message '1 record inserted'", w.contains("1 record inserted"));

            String w2 = rpc(out, in, "{\"requestId\":\"4\",\"action\":\"WRITE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Ayse\",\"sinif\":3}}");
            check("ikinci WRITE OK", w2.contains("\"status\":\"OK\""));
            rpc(out, in, "{\"requestId\":\"4b\",\"action\":\"WRITE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Veli\",\"sinif\":5}}");

            System.out.println("--- READ + filter (Ister_0016) ---");
            String r = rpc(out, in, "{\"requestId\":\"5\",\"action\":\"READ\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"sinif\":3}}");
            check("READ 2 records (sinif=3)", r.contains("2 record(s) found"));
            check("READ data listesi", r.contains("Ali") && r.contains("Ayse") && !r.contains("Veli"));

            String rAll = rpc(out, in, "{\"requestId\":\"6\",\"action\":\"READ\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("filtresiz READ tum kayitlar (3)", rAll.contains("3 record(s) found"));

            System.out.println("--- UPDATE ---");
            String u = rpc(out, in, "{\"requestId\":\"7\",\"action\":\"UPDATE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"sinif\":3},\"document\":{\"gecti\":true}}");
            check("UPDATE 2 records updated", u.contains("2 record(s) updated"));

            System.out.println("--- DELETE ---");
            String d = rpc(out, in, "{\"requestId\":\"8\",\"action\":\"DELETE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"sinif\":5}}");
            check("DELETE 1 record deleted", d.contains("1 record(s) deleted"));

            System.out.println("--- LIST_DATABASES / LIST_COLLECTIONS ---");
            String ld = rpc(out, in, "{\"requestId\":\"9\",\"action\":\"LIST_DATABASES\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}");
            check("LIST_DATABASES okul iceriyor", ld.contains("okul"));
            String lc = rpc(out, in, "{\"requestId\":\"10\",\"action\":\"LIST_COLLECTIONS\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"okul\"}");
            check("LIST_COLLECTIONS ogrenciler iceriyor", lc.contains("ogrenciler"));

            System.out.println("--- Yetki: sinirli kullanici DELETE yapamaz ---");
            String noDel = rpc(out, in, "{\"requestId\":\"11\",\"action\":\"DELETE\",\"username\":\"mehmet@company.com\",\"password\":\"3QPKdvlca34avSl\",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{}}");
            check("mehmet DELETE -> UNAUTHORIZED", noDel.contains("\"status\":\"UNAUTHORIZED\""));
            String canRead = rpc(out, in, "{\"requestId\":\"12\",\"action\":\"READ\",\"username\":\"mehmet@company.com\",\"password\":\"3QPKdvlca34avSl\",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("mehmet READ -> OK (dataView var)", canRead.contains("\"status\":\"OK\""));

            System.out.println("--- Bozuk JSON baglantiyi koparmiyor ---");
            String garbage = rpc(out, in, "bu bozuk bir satir {{{");
            check("bozuk satir cevap doner", garbage != null && (garbage.contains("ERROR") || garbage.contains("\"ok\":false")));
            String stillAlive = rpc(out, in, "{\"requestId\":\"13\",\"action\":\"PING\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}");
            check("bozuk satirdan sonra baglanti yasiyor", stillAlive.contains("\"status\":\"OK\""));

            System.out.println("--- IKI PROTOKOL AYNI VERIYI PAYLASIYOR ---");
            // Backend WRITE ile ekle, frontend protokolu ile oku
            rpc(out, in, "{\"requestId\":\"14\",\"action\":\"WRITE\",\"username\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\",\"database\":\"paylasim\",\"collection\":\"test\",\"document\":{\"kaynak\":\"backend\"}}");
            String feLogin = rpc(out, in, "{\"requestId\":\"15\",\"action\":\"auth.login\",\"payload\":{\"email\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}}");
            String token = extract(feLogin, "token");
            String feRead = rpc(out, in, "{\"requestId\":\"16\",\"action\":\"records.list\",\"token\":\"" + token + "\",\"payload\":{\"databaseId\":\"paylasim\",\"collectionName\":\"test\"}}");
            check("backend'in yazdigini frontend okuyabiliyor", feRead.contains("backend") && feRead.contains("\"ok\":true"));
        }

        System.out.println("\n==========================================");
        System.out.println(" RESULT: " + ok + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? " ALL BACKEND PROTOCOL TESTS PASSED" : " SOME TESTS FAILED");
        System.out.println("==========================================");
        if (fail > 0) System.exit(1);
    }

    static String rpc(PrintWriter out, BufferedReader in, String msg) throws Exception {
        out.println(msg);
        return in.readLine();
    }

    static void check(String name, boolean cond) {
        if (cond) { ok++; System.out.println("  [OK]   " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        int s = i + key.length() + 4;
        return json.substring(s, json.indexOf('"', s));
    }
}
