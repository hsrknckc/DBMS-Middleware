package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class AuthProtocolTest {

    static String host = "localhost";
    static int port = 5000;
    static int ok = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) host = args[0];
        if (args.length > 1) port = Integer.parseInt(args[1]);

        try (Socket s = new Socket(host, port);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {

            System.out.println("--- Protokol zarfi ---");
            String r = rpc(out, in, "req-1", "ping", null, "{}");
            check("requestId geri donuyor", r.contains("\"requestId\":\"req-1\""));
            check("ok:true bicimi", r.contains("\"ok\":true"));

            System.out.println("--- Yetkisiz erisim reddi ---");
            String noauth = rpc(out, in, "req-2", "databases.list", null, "{\"includeDeleted\":false}");
            check("token'siz istek reddedilir", noauth.contains("\"ok\":false") && noauth.contains("authenticated"));

            System.out.println("--- auth.login (super admin) ---");
            String login = rpc(out, in, "req-3", "auth.login", null,
                    "{\"email\":\"ayse@company.com\",\"password\":\"Ddsfkln1as1sFd\"}");
            check("login basarili", login.contains("\"ok\":true"));
            check("token donuyor", login.contains("\"token\":\"tok-"));
            check("role superAdmin", login.contains("\"role\":\"superAdmin\""));
            String adminToken = extract(login, "token");

            System.out.println("--- Wrong password rejection ---");
            String bad = rpc(out, in, "req-4", "auth.login", null,
                    "{\"email\":\"ayse@company.com\",\"password\":\"yanlis\"}");
            check("wrong password is rejected", bad.contains("\"ok\":false"));

            System.out.println("--- Super admin: database olusturma ---");
            String dbc = rpc(out, in, "req-5", "databases.create", adminToken,
                    "{\"name\":\"SensorDB\",\"department\":\"Sensor\",\"description\":\"olcumler\"}");
            check("database olusturuldu", dbc.contains("\"ok\":true") && dbc.contains("SensorDB"));
            check("id atandi", dbc.contains("\"id\":\"rec-"));
            String dbId = extract(dbc, "id");

            System.out.println("--- databases.list (yetkili) ---");
            String dbl = rpc(out, in, "req-6", "databases.list", adminToken, "{\"includeDeleted\":false}");
            check("liste geliyor", dbl.contains("SensorDB"));

            System.out.println("--- soft delete + restore ---");
            String sd = rpc(out, in, "req-7", "databases.softDelete", adminToken, "{\"id\":\"" + dbId + "\"}");
            check("soft delete isDeleted=true", sd.contains("\"isDeleted\":true"));
            String activeList = rpc(out, in, "req-8", "databases.list", adminToken, "{\"includeDeleted\":false}");
            check("soft-deleted db not in active list", !activeList.contains(dbId));
            String rs = rpc(out, in, "req-9", "databases.restore", adminToken, "{\"id\":\"" + dbId + "\"}");
            check("restore isDeleted=false", rs.contains("\"isDeleted\":false"));

            System.out.println("--- records CRUD (Data Explorer) ---");
            String rc = rpc(out, in, "req-10", "records.create", adminToken,
                    "{\"databaseId\":\"db-1\",\"collectionName\":\"sensor_readings\","
                    + "\"data\":{\"sensorId\":\"SEN-001\",\"value\":22.8}}");
            check("record created", rc.contains("\"ok\":true") && rc.contains("SEN-001"));
            String recId = extract(rc, "id");
            String rl = rpc(out, in, "req-11", "records.list", adminToken,
                    "{\"databaseId\":\"db-1\",\"collectionName\":\"sensor_readings\"}");
            check("record listed", rl.contains("SEN-001"));

            System.out.println("--- dashboard.stats ---");
            String ds = rpc(out, in, "req-12", "dashboard.stats", adminToken, "{}");
            check("stats totalDatabases", ds.contains("totalDatabases"));
            check("stats activeUsers", ds.contains("activeUsers"));

            System.out.println("--- Sinirli kullanici yetki testi ---");
            String userLogin = rpc(out, in, "req-13", "auth.login", null,
                    "{\"email\":\"mehmet@company.com\",\"password\":\"3QPKdvlca34avSl\"}");
            String userToken = extract(userLogin, "token");
            check("normal kullanici login", userLogin.contains("\"ok\":true"));
            // Bu kullanicida dataCreate VAR, dataDelete YOK
            String allowedCreate = rpc(out, in, "req-14", "records.create", userToken,
                    "{\"databaseId\":\"db-1\",\"collectionName\":\"c\",\"data\":{\"x\":1}}");
            check("authorized action (dataCreate) passes", allowedCreate.contains("\"ok\":true"));
            String deniedDelete = rpc(out, in, "req-15", "records.delete", userToken,
                    "{\"databaseId\":\"db-1\",\"collectionName\":\"c\",\"id\":\"rec-1\"}");
            check("unauthorized action (dataDelete) is rejected",
                    deniedDelete.contains("\"ok\":false") && deniedDelete.contains("Permission denied"));
            // Normal kullanici users.list yapamaz (super admin gerekir)
            String deniedUsers = rpc(out, in, "req-16", "users.list", userToken, "{}");
            check("normal kullanici users.list yapamaz", deniedUsers.contains("Super admin required"));

            System.out.println("--- auth.me ve logout ---");
            String me = rpc(out, in, "req-17", "auth.me", adminToken, "{}");
            check("auth.me kullaniciyi doner", me.contains("ayse@company.com"));
            String lo = rpc(out, in, "req-18", "auth.logout", adminToken, "{}");
            check("logout ok", lo.contains("\"ok\":true"));
            String afterLogout = rpc(out, in, "req-19", "databases.list", adminToken, "{}");
            check("logout sonrasi token gecersiz", afterLogout.contains("Not authenticated"));
        }

        System.out.println("\n==========================================");
        System.out.println(" RESULT: " + ok + " passed, " + fail + " failed");
        System.out.println(fail == 0 ? " ALL PROTOCOL+AUTH TESTS PASSED" : " SOME TESTS FAILED");
        System.out.println("==========================================");
        if (fail > 0) System.exit(1);
    }

    static String rpc(PrintWriter out, BufferedReader in, String rid, String action,
                      String token, String payloadJson) throws Exception {
        StringBuilder sb = new StringBuilder("{\"requestId\":\"").append(rid)
                .append("\",\"action\":\"").append(action).append("\"");
        if (token != null) sb.append(",\"token\":\"").append(token).append("\"");
        sb.append(",\"payload\":").append(payloadJson).append("}");
        out.println(sb);
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
