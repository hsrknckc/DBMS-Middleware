package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * TEK PROTOKOL testi (PROTOKOL.md).
 *
 *   Terminal 1: java -cp "out;lib/*" middleware.Main
 *   Terminal 2: java -cp "out;lib/*" middleware.ProtocolTest
 *
 * Ilk argumanla adres, ikinciyle port verilebilir:
 *   java -cp "out;lib/*" middleware.ProtocolTest 54.154.220.190 5150
 */
public class ProtocolTest {

    static final String ADMIN_USER = "ayse@company.com";
    static final String ADMIN_PASS = "Ddsfkln1as1sFd";
    static final String LIMITED_USER = "mehmet@company.com";
    static final String LIMITED_PASS = "3QPKdvlca34avSl";

    static String host = "localhost";
    static int port = 5150;
    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);

        System.out.println("Protocol test -> " + host + ":" + port + "\n");

        try (Socket s = new Socket(host, port);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            section("Envelope and PING");
            String r = rpc(out, in, "{\"requestId\":\"1\",\"action\":\"PING\"," + admin() + "}");
            check("requestId is echoed back", r.contains("\"requestId\":\"1\""));
            check("status OK", r.contains("\"status\":\"OK\""));
            check("data is always an array", r.contains("\"data\":["));

            section("Authentication");
            String bad = rpc(out, in, "{\"requestId\":\"2\",\"action\":\"PING\","
                    + "\"username\":\"" + ADMIN_USER + "\",\"password\":\"wrong\"}");
            check("wrong password -> UNAUTHORIZED", bad.contains("\"status\":\"UNAUTHORIZED\""));

            String login = rpc(out, in, "{\"requestId\":\"3\",\"action\":\"LOGIN\"," + admin() + "}");
            check("LOGIN returns OK", login.contains("\"status\":\"OK\""));
            check("LOGIN returns role", login.contains("\"role\":\"superAdmin\""));
            check("LOGIN returns permissions", login.contains("\"permissions\""));
            check("LOGIN never returns password", !login.contains(ADMIN_PASS));

            section("Database management");
            String dbc = rpc(out, in, "{\"requestId\":\"4\",\"action\":\"CREATE_DATABASE\"," + admin()
                    + ",\"database\":\"okul\",\"document\":{\"department\":\"Sensor\",\"description\":\"deneme\"}}");
            check("CREATE_DATABASE ok", dbc.contains("\"status\":\"OK\""));
            check("metadata returned", dbc.contains("\"department\":\"Sensor\""));
            check("collections field present", dbc.contains("\"collections\":["));

            String dup = rpc(out, in, "{\"requestId\":\"5\",\"action\":\"CREATE_DATABASE\"," + admin()
                    + ",\"database\":\"okul\",\"document\":{}}");
            check("duplicate database rejected", dup.contains("already exists"));

            String reserved = rpc(out, in, "{\"requestId\":\"6\",\"action\":\"CREATE_DATABASE\"," + admin()
                    + ",\"database\":\"__meta__\",\"document\":{}}");
            check("reserved name rejected", reserved.contains("Reserved"));

            section("Collection management");
            String cc = rpc(out, in, "{\"requestId\":\"7\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("CREATE_COLLECTION ok", cc.contains("\"status\":\"OK\""));

            String lc = rpc(out, in, "{\"requestId\":\"8\",\"action\":\"LIST_COLLECTIONS\"," + admin()
                    + ",\"database\":\"okul\"}");
            check("empty collection is listed", lc.contains("ogrenciler"));
            check("LIST_COLLECTIONS returns plain names", lc.contains("[\"ogrenciler\"]"));

            String ccDup = rpc(out, in, "{\"requestId\":\"9\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("duplicate collection rejected", ccDup.contains("already exists"));

            section("Records: WRITE / READ / UPDATE / DELETE");
            rpc(out, in, "{\"requestId\":\"10\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Ali\",\"sinif\":3}}");
            rpc(out, in, "{\"requestId\":\"11\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Ayse\",\"sinif\":3}}");
            String w3 = rpc(out, in, "{\"requestId\":\"12\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Veli\",\"sinif\":5}}");
            check("WRITE returns inserted record", w3.contains("\"id\":\"rec-") && w3.contains("Veli"));

            String rd = rpc(out, in, "{\"requestId\":\"13\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"sinif\":3}}");
            check("READ with filter -> 2 records", rd.contains("2 record(s) found"));
            check("filter excludes others", rd.contains("Ali") && !rd.contains("Veli"));

            String rdAll = rpc(out, in, "{\"requestId\":\"14\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("READ without filter -> all 3", rdAll.contains("3 record(s) found"));

            String up = rpc(out, in, "{\"requestId\":\"15\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\","
                    + "\"filter\":{\"sinif\":3},\"document\":{\"gecti\":true}}");
            check("UPDATE affects 2 records", up.contains("2 record(s) updated"));

            String del = rpc(out, in, "{\"requestId\":\"16\",\"action\":\"DELETE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"sinif\":5}}");
            check("DELETE removes 1 record", del.contains("1 record(s) deleted"));

            section("LIST_DATABASES and LIST_DATABASES_INFO");
            String ld = rpc(out, in, "{\"requestId\":\"17\",\"action\":\"LIST_DATABASES\"," + admin() + "}");
            check("LIST_DATABASES contains okul", ld.contains("okul"));
            check("meta database is hidden", !ld.contains("__meta__"));

            String ldi = rpc(out, in, "{\"requestId\":\"18\",\"action\":\"LIST_DATABASES_INFO\"," + admin() + "}");
            check("INFO returns metadata", ldi.contains("\"description\":\"deneme\""));
            check("INFO returns recordCount", ldi.contains("\"recordCount\""));
            check("INFO returns collectionCount", ldi.contains("\"collectionCount\""));

            section("Soft delete / restore / drop");
            String sd = rpc(out, in, "{\"requestId\":\"19\",\"action\":\"DELETE_DATABASE\"," + admin()
                    + ",\"database\":\"okul\"}");
            check("DELETE_DATABASE sets isDeleted", sd.contains("\"isDeleted\":true"));

            String ldAfter = rpc(out, in, "{\"requestId\":\"20\",\"action\":\"LIST_DATABASES\"," + admin() + "}");
            check("deleted db hidden from list", !ldAfter.contains("okul"));

            String trash = rpc(out, in, "{\"requestId\":\"21\",\"action\":\"LIST_DATABASES_INFO\"," + admin()
                    + ",\"filter\":{\"includeDeleted\":true}}");
            check("trash view shows deleted db", trash.contains("okul"));

            String rs = rpc(out, in, "{\"requestId\":\"22\",\"action\":\"RESTORE_DATABASE\"," + admin()
                    + ",\"database\":\"okul\"}");
            check("RESTORE brings it back", rs.contains("\"isDeleted\":false"));

            String survived = rpc(out, in, "{\"requestId\":\"23\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("records survived soft delete", survived.contains("2 record(s) found"));

            section("Permissions (Ister_0013, Ister_0015)");
            String limitedRead = rpc(out, in, "{\"requestId\":\"24\",\"action\":\"READ\"," + limited()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("limited user CAN read (dataView)", limitedRead.contains("\"status\":\"OK\""));

            String limitedDelete = rpc(out, in, "{\"requestId\":\"25\",\"action\":\"DELETE\"," + limited()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{}}");
            check("limited user CANNOT delete", limitedDelete.contains("\"status\":\"UNAUTHORIZED\""));

            String limitedDb = rpc(out, in, "{\"requestId\":\"26\",\"action\":\"CREATE_DATABASE\"," + limited()
                    + ",\"database\":\"yeni\",\"document\":{}}");
            check("limited user CANNOT create database", limitedDb.contains("\"status\":\"UNAUTHORIZED\""));

            String limitedUsers = rpc(out, in, "{\"requestId\":\"27\",\"action\":\"LIST_USERS\"," + limited() + "}");
            check("limited user CANNOT list users", limitedUsers.contains("Super admin required"));

            section("User management");
            String lu = rpc(out, in, "{\"requestId\":\"28\",\"action\":\"LIST_USERS\"," + admin() + "}");
            check("admin can list users", lu.contains(ADMIN_USER) && lu.contains(LIMITED_USER));
            check("passwords are never exposed", !lu.contains(LIMITED_PASS));

            String cu = rpc(out, in, "{\"requestId\":\"29\",\"action\":\"CREATE_USER\"," + admin()
                    + ",\"document\":{\"name\":\"Yeni Kisi\",\"email\":\"yeni@company.com\","
                    + "\"password\":\"gecici123\",\"role\":\"user\","
                    + "\"departments\":[\"Sensor\"],\"permissions\":[\"dataView\"]}}");
            check("CREATE_USER ok", cu.contains("yeni@company.com"));

            String cuDup = rpc(out, in, "{\"requestId\":\"30\",\"action\":\"CREATE_USER\"," + admin()
                    + ",\"document\":{\"email\":\"yeni@company.com\"}}");
            check("duplicate email rejected", cuDup.contains("already exists"));

            section("STATS");
            String st = rpc(out, in, "{\"requestId\":\"31\",\"action\":\"STATS\"," + admin() + "}");
            check("stats has totalDatabases", st.contains("totalDatabases"));
            check("stats has totalRecords", st.contains("totalRecords"));
            check("stats has activeUsers", st.contains("activeUsers"));

            section("Robustness");
            String garbage = rpc(out, in, "this is not json {{{");
            check("malformed JSON -> ERROR", garbage.contains("\"status\":\"ERROR\""));
            String alive = rpc(out, in, "{\"requestId\":\"32\",\"action\":\"PING\"," + admin() + "}");
            check("connection survives bad input", alive.contains("\"status\":\"OK\""));

            String unknown = rpc(out, in, "{\"requestId\":\"33\",\"action\":\"NOPE\"," + admin() + "}");
            check("unknown action -> ERROR", unknown.contains("Unknown action"));

            String missing = rpc(out, in, "{\"requestId\":\"34\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\"}");
            check("missing collection -> ERROR", missing.contains("collection field is required"));

            section("DROP_DATABASE (permanent)");
            String drop = rpc(out, in, "{\"requestId\":\"35\",\"action\":\"DROP_DATABASE\"," + admin()
                    + ",\"database\":\"okul\"}");
            check("DROP_DATABASE ok", drop.contains("\"status\":\"OK\""));
            String gone = rpc(out, in, "{\"requestId\":\"36\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\"}");
            check("records are gone after drop", gone.contains("0 record(s) found"));
        }

        System.out.println("\n==========================================");
        System.out.println(" RESULT: " + ok + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? " ALL PROTOCOL TESTS PASSED" : " SOME TESTS FAILED");
        System.out.println("==========================================");
        if (fail > 0) System.exit(1);
    }

    static String admin() {
        return "\"username\":\"" + ADMIN_USER + "\",\"password\":\"" + ADMIN_PASS + "\"";
    }

    static String limited() {
        return "\"username\":\"" + LIMITED_USER + "\",\"password\":\"" + LIMITED_PASS + "\"";
    }

    static String rpc(PrintWriter out, BufferedReader in, String msg) throws Exception {
        out.println(msg);
        return in.readLine();
    }

    static void section(String title) {
        System.out.println("--- " + title + " ---");
    }

    static void check(String name, boolean cond) {
        if (cond) { ok++; System.out.println("  [OK]   " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }
}
