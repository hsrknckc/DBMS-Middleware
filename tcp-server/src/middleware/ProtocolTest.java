package middleware;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ProtocolTest {

    static final String ADMIN_USER = "ayse@company.com";
    static final String ADMIN_PASS = "Ddsfkln1as1sFd";
    static final String LIMITED_USER = "mehmet@company.com";
    static final String LIMITED_PASS = "3QPKdvlca34avSl";

    static final String TEST_DB = "protokol_test_db";
    static final String TEST_FILE = "protokol-test.json";
    static final String BROKEN_FILE = "protokol-test-bozuk.json";

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

            section("Optional name field");
            String withName = rpc(out, in, "{\"requestId\":\"n1\",\"action\":\"PING\"," + admin()
                    + ",\"name\":\"Ayse Yilmaz\"}");
            check("request with name field accepted", withName.contains("\"status\":\"OK\""));

            String wrongName = rpc(out, in, "{\"requestId\":\"n2\",\"action\":\"PING\"," + admin()
                    + ",\"name\":\"Baska Kisi\"}");
            check("mismatching name does not block the request",
                    wrongName.contains("\"status\":\"OK\""));

            String loginName = rpc(out, in, "{\"requestId\":\"n3\",\"action\":\"LOGIN\"," + admin()
                    + ",\"name\":\"Yanlis Ad\"}");
            check("stored name is authoritative, not the claimed one",
                    loginName.contains("\"name\":\"") && !loginName.contains("\"name\":\"Yanlis Ad\""));

            section("Authentication");
            String bad = rpc(out, in, "{\"requestId\":\"2\",\"action\":\"PING\","
                    + "\"username\":\"" + ADMIN_USER + "\",\"password\":\"wrong\"}");
            check("wrong password -> UNAUTHORIZED", bad.contains("\"status\":\"UNAUTHORIZED\""));

            String login = rpc(out, in, "{\"requestId\":\"3\",\"action\":\"LOGIN\"," + admin() + "}");
            String adminId = extract(login, "id");
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

            String massDel = rpc(out, in, "{\"requestId\":\"del_mass\",\"action\":\"DELETE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{}}");
            check("DELETE with empty filter is rejected", massDel.contains("\"status\":\"ERROR\"") 
                && massDel.contains("Mass deletion with empty filter is not allowed"));

            String massUp = rpc(out, in, "{\"requestId\":\"up_mass\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{},\"document\":{\"ad\":\"Hepsi\"}}");
            check("UPDATE with empty filter is rejected", massUp.contains("\"status\":\"ERROR\"")
                && massUp.contains("Mass update with empty filter is not allowed"));

            section("Filter tolerance (_id alias) and delete count");
            rpc(out, in, "{\"requestId\":\"13a\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Silinecek\"}}");
            String toDelete = rpc(out, in, "{\"requestId\":\"13b\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"ad\":\"Silinecek\"}}");
            String delId = extract(toDelete, "id");

            // Istemci MongoDB alistigi icin hem id hem _id gonderebilir;
            // _id kayitlarda bulunmadigi icin eskiden hicbir sey silinmezdi.
            String delBoth = rpc(out, in, "{\"requestId\":\"13c\",\"action\":\"DELETE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\","
                    + "\"filter\":{\"id\":\"" + delId + "\",\"_id\":\"" + delId + "\"}}");
            check("filter with both id and _id deletes the record", delBoth.contains("1 record(s) deleted"));
            check("DELETE reports deletedCount", delBoth.contains("\"deletedCount\":1"));

            String delNone = rpc(out, in, "{\"requestId\":\"13d\",\"action\":\"DELETE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"id\":\"yok\"}}");
            check("no match reports deletedCount 0", delNone.contains("\"deletedCount\":0"));

            String readAlias = rpc(out, in, "{\"requestId\":\"13e\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"_id\":\"" + delId + "\"}}");
            check("_id filter finds nothing after delete", readAlias.contains("0 record(s) found"));

            section("Advanced Filters and Security (Sanitization)");

            rpc(out, in, "{\"requestId\":\"f_setup1\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\"}");
            rpc(out, in, "{\"requestId\":\"f_setup2\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"document\":{\"ad\":\"Bora\",\"sinif\":2}}");
            rpc(out, in, "{\"requestId\":\"f_setup3\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"document\":{\"ad\":\"Ceren\",\"sinif\":4}}");
            rpc(out, in, "{\"requestId\":\"f_setup4\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"document\":{\"ad\":\"Deniz\",\"sinif\":5}}");

            // 10. IN bos liste
String inEmpty = rpc(out, in,
        "{\"requestId\":\"f_in_empty\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"filter\":{\"sinif\":{\"in\":[]}}}");

check("in with empty list is rejected or returns no records",
        inEmpty.contains("\"status\":\"ERROR\"")
        || inEmpty.contains("0 record(s) found"));


// 11. BETWEEN ters aralik
String betweenInvalid = rpc(out, in,
        "{\"requestId\":\"f_btw_invalid\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"filter\":{\"sinif\":{\"between\":[5,3]}}}");

check("between with reversed range is rejected or returns no records",
        betweenInvalid.contains("\"status\":\"ERROR\"")
        || betweenInvalid.contains("0 record(s) found"));


// 12. Negatif limit
String negativeLimit = rpc(out, in,
        "{\"requestId\":\"f_limit_negative\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"limit\":-1}");

check("negative limit is rejected",
        negativeLimit.contains("\"status\":\"ERROR\""));


// 13. Negatif skip
String negativeSkip = rpc(out, in,
        "{\"requestId\":\"f_skip_negative\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"skip\":-1}");

check("negative skip is rejected",
        negativeSkip.contains("\"status\":\"ERROR\""));


// 14. Geçersiz sort yönü
String invalidSort = rpc(out, in,
        "{\"requestId\":\"f_sort_invalid\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"sort\":{\"sinif\":\"hacked\"}}");

check("invalid sort order is rejected",
        invalidSort.contains("\"status\":\"ERROR\""));


// 15. Aşırı büyük limit
String hugeLimit = rpc(out, in,
        "{\"requestId\":\"f_limit_huge\",\"action\":\"READ\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\","
        + "\"limit\":999999999}");

check("excessive limit is rejected",
        hugeLimit.contains("\"status\":\"ERROR\""));
            // 1. Karşılaştırma Operatörü (gt / >) Testi
            String gtTest = rpc(out, in, "{\"requestId\":\"f_gt\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\">\":3}}}");
            check("gt filter matches records > 3",
                    gtTest.contains("2 record(s) found") && gtTest.contains("Ceren") && gtTest.contains("Deniz"));

            // 1b. Takma ad operatörler de çalışmalı
            String gteTest = rpc(out, in, "{\"requestId\":\"f_gte\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"gte\":4}}}");
            check("gte alias matches records >= 4",
                    gteTest.contains("2 record(s) found") && gteTest.contains("Ceren") && gteTest.contains("Deniz"));

            String lteTest = rpc(out, in, "{\"requestId\":\"f_lte\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"lte\":4}}}");
            check("lte alias matches records <= 4",
                    lteTest.contains("2 record(s) found") && lteTest.contains("Bora") && lteTest.contains("Ceren"));

            // 2. Metin Arama (like / case-insensitive) Testi
            String likeTest = rpc(out, in, "{\"requestId\":\"f_like\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"ad\":{\"like\":\"al\"}}}");
            check("like filter matches substring case-insensitive", likeTest.contains("1 record(s) found") && likeTest.contains("Ali"));

            // 3. Eşit Değil (ne / !=) Testi
            String neTest = rpc(out, in, "{\"requestId\":\"f_ne\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"!=\":4}}}");
            check("ne filter excludes matching records",
                    neTest.contains("2 record(s) found") && neTest.contains("Bora")
                            && neTest.contains("Deniz") && !neTest.contains("Ceren"));

            // 4. NoSQL Injection Koruması ($where operatörü engellenmeli)
            String injectionTest = rpc(out, in, "{\"requestId\":\"f_inj\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"filter\":{\"$where\":\"sleep(1000)\"}}");
            check("NoSQL injection with $ operator is rejected", injectionTest.contains("\"status\":\"ERROR\"")
                    && injectionTest.contains("Dangerous query operator"));

            String nestedInjectionTest = rpc(out, in, "{\"requestId\":\"f_inj2\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"$gt\":3}}}");
            check("nested $ operator injection is rejected", nestedInjectionTest.contains("\"status\":\"ERROR\"")
                    && nestedInjectionTest.contains("Dangerous query operator"));

            // 5. Desteklenmeyen / Bilinmeyen Operatör Testi
            String invalidOpTest = rpc(out, in, "{\"requestId\":\"f_inv\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"foo\":1}}}");
            check("unsupported filter operator returns ERROR", invalidOpTest.contains("\"status\":\"ERROR\"")
                    && invalidOpTest.contains("Unsupported filter operator"));
            // 6. IN ve NIN Operatör Testleri
            String inTest = rpc(out, in, "{\"requestId\":\"f_in\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"in\":[2, 5]}}}");
            check("in operator matches specified values", inTest.contains("2 record(s) found") 
                    && inTest.contains("Bora") && inTest.contains("Deniz") && !inTest.contains("Ceren"));

            String ninTest = rpc(out, in, "{\"requestId\":\"f_nin\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"nin\":[2, 5]}}}");
            check("nin operator excludes specified values", ninTest.contains("1 record(s) found") 
                    && ninTest.contains("Ceren"));

            // 7. BETWEEN Operatör Testi
            String betweenTest = rpc(out, in, "{\"requestId\":\"f_btw\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"filter\":{\"sinif\":{\"between\":[3, 5]}}}");
            check("between operator matches inclusive range", betweenTest.contains("2 record(s) found") 
                    && betweenTest.contains("Ceren") && betweenTest.contains("Deniz"));

            // 8. SIRALAMA (Sort) Testi
            String sortAsc = rpc(out, in, "{\"requestId\":\"f_sort_asc\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"sort\":{\"sinif\":\"asc\"}}");
            check("sort ascending works", sortAsc.indexOf("Bora") < sortAsc.indexOf("Ceren") 
                    && sortAsc.indexOf("Ceren") < sortAsc.indexOf("Deniz"));

            String sortDesc = rpc(out, in, "{\"requestId\":\"f_sort_desc\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"sort\":{\"sinif\":\"desc\"}}");
            check("sort descending works", sortDesc.indexOf("Deniz") < sortDesc.indexOf("Ceren") 
                    && sortDesc.indexOf("Ceren") < sortDesc.indexOf("Bora"));

            // 9. SAYFALAMA (Limit & Skip) Testi
            String pageTest = rpc(out, in, "{\"requestId\":\"f_page\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"filtre_testleri\",\"sort\":{\"sinif\":\"asc\"},\"skip\":1,\"limit\":1}");
            check("skip and limit returns exact page slice", pageTest.contains("1 record(s) found") 
                    && pageTest.contains("Ceren") && !pageTest.contains("Bora") && !pageTest.contains("Deniz"));
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

            // Dosya testleri sunucu ile AYNI makinede calisirken anlamlidir;
            // uzak sunucuya karsi calistirilirken atlanir.
            if (host.equals("localhost") || host.equals("127.0.0.1")) {
                section("Request file (Ister_0011 / Ister_0012)");
                prepareRequestFiles();

                String chk = rpc(out, in, "{\"requestId\":\"f1\",\"action\":\"CHECK_FILE\"," + admin()
                        + ",\"document\":{\"path\":\"" + TEST_FILE + "\"}}");
                check("existing file reported as present", chk.contains("\"exists\":true"));
                check("file content recognised as valid JSON", chk.contains("\"validJson\":true"));

                String missing = rpc(out, in, "{\"requestId\":\"f2\",\"action\":\"CHECK_FILE\"," + admin()
                        + ",\"document\":{\"path\":\"olmayan-dosya.json\"}}");
                check("missing file reported as absent", missing.contains("\"exists\":false"));

                String broken = rpc(out, in, "{\"requestId\":\"f3\",\"action\":\"CHECK_FILE\"," + admin()
                        + ",\"document\":{\"path\":\"" + BROKEN_FILE + "\"}}");
                check("invalid JSON detected", broken.contains("\"exists\":true")
                        && broken.contains("\"validJson\":false"));

                String escape = rpc(out, in, "{\"requestId\":\"f4\",\"action\":\"CHECK_FILE\"," + admin()
                        + ",\"document\":{\"path\":\"../../etc/passwd\"}}");
                check("path traversal is rejected", escape.contains("outside the request directory"));

                String imp = rpc(out, in, "{\"requestId\":\"f5\",\"action\":\"IMPORT_FILE\"," + admin()
                        + ",\"document\":{\"path\":\"" + TEST_FILE + "\"}}");
                check("IMPORT_FILE succeeds", imp.contains("Import completed"));
                check("collections created from file", imp.contains("test_kayitlar"));
                check("records inserted from file", imp.contains("\"recordsInserted\":2"));

                String cols = rpc(out, in, "{\"requestId\":\"f6\",\"action\":\"LIST_COLLECTIONS\"," + admin()
                        + ",\"database\":\"" + TEST_DB + "\"}");
                check("imported collection is listed", cols.contains("test_kayitlar"));

                String desc = rpc(out, in, "{\"requestId\":\"f7\",\"action\":\"DESCRIBE_COLLECTION\"," + admin()
                        + ",\"database\":\"" + TEST_DB + "\",\"collection\":\"test_kayitlar\"}");
                check("field definitions are stored", desc.contains("\"name\":\"baslik\""));

                rpc(out, in, "{\"requestId\":\"f8\",\"action\":\"DROP_DATABASE\"," + admin()
                        + ",\"database\":\"" + TEST_DB + "\"}");
            }

            section("User management (Ister_0004 / Ister_0005)");
            String cu2 = rpc(out, in, "{\"requestId\":\"u1\",\"action\":\"CREATE_USER\"," + admin()
                    + ",\"document\":{\"name\":\"Test Kisi\",\"email\":\"testuser@company.com\","
                    + "\"password\":\"gecici123\",\"role\":\"user\","
                    + "\"departments\":[\"Sensor\"],\"permissions\":[\"dataView\"]}}");
            check("CREATE_USER ok", cu2.contains("testuser@company.com"));
            String newUserId = extract(cu2, "id");

            String uu = rpc(out, in, "{\"requestId\":\"u2\",\"action\":\"UPDATE_USER\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"},\"document\":{\"name\":\"Yeni Ad\"}}");
            check("UPDATE_USER changes name", uu.contains("Yeni Ad"));

            String upPerm = rpc(out, in, "{\"requestId\":\"u3\",\"action\":\"UPDATE_USER_PERMISSIONS\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"},"
                    + "\"document\":{\"permissions\":[\"dataView\",\"dataCreate\"]}}");
            check("UPDATE_USER_PERMISSIONS applies", upPerm.contains("dataCreate"));

            String rp = rpc(out, in, "{\"requestId\":\"u4\",\"action\":\"RESET_USER_PASSWORD\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"},\"document\":{\"password\":\"yeniSifre1\"}}");
            check("RESET_USER_PASSWORD ok", rp.contains("\"status\":\"OK\""));
            String relogin = rpc(out, in, "{\"requestId\":\"u5\",\"action\":\"LOGIN\","
                    + "\"username\":\"testuser@company.com\",\"password\":\"yeniSifre1\"}");
            check("login works with new password", relogin.contains("\"status\":\"OK\""));

            String du = rpc(out, in, "{\"requestId\":\"u6\",\"action\":\"DELETE_USER\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"}}");
            check("DELETE_USER marks as deleted", du.contains("\"isDeleted\":true"));

            String lu2 = rpc(out, in, "{\"requestId\":\"u7\",\"action\":\"LIST_USERS\"," + admin() + "}");
            check("deleted user hidden from list", !lu2.contains("testuser@company.com"));

            String luAll = rpc(out, in, "{\"requestId\":\"u8\",\"action\":\"LIST_USERS\"," + admin()
                    + ",\"filter\":{\"includeDeleted\":true}}");
            check("trash view shows deleted user", luAll.contains("testuser@company.com"));

            String ru = rpc(out, in, "{\"requestId\":\"u9\",\"action\":\"RESTORE_USER\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"}}");
            check("RESTORE_USER brings it back", ru.contains("\"isDeleted\":false"));

            String self = rpc(out, in, "{\"requestId\":\"u10\",\"action\":\"DELETE_USER\"," + admin()
                    + ",\"filter\":{\"id\":\"" + adminId + "\"}}");
            check("cannot delete own account", self.contains("your own account"));

            String dropU = rpc(out, in, "{\"requestId\":\"u11\",\"action\":\"DROP_USER\"," + admin()
                    + ",\"filter\":{\"id\":\"" + newUserId + "\"}}");
            check("DROP_USER removes permanently", dropU.contains("\"status\":\"OK\""));

            section("Audit log");
            String logs = rpc(out, in, "{\"requestId\":\"a1\",\"action\":\"AUDIT_LOGS\"," + admin() + "}");
            check("audit log records user actions", logs.contains("userCreated"));
            check("audit log stores old values", logs.contains("oldValues"));

            String revertible = rpc(out, in, "{\"requestId\":\"a2\",\"action\":\"AUDIT_LOGS\"," + admin()
                    + ",\"filter\":{\"onlyRevertible\":true}}");
            check("revertible filter works", revertible.contains("\"isRevertible\":true"));
section("CRUD Audit & Revert Tests");

            // 1. Yeni kayıt ekle (WRITE -> Audit log oluşmalı)
            String auditDb = "test_audit_db";
            String auditCol = "urunler";
            rpc(out, in, "{\"requestId\":\"a_db1\",\"action\":\"CREATE_DATABASE\"," + admin() + ",\"database\":\"" + auditDb + "\"}");
            rpc(out, in, "{\"requestId\":\"a_col1\",\"action\":\"CREATE_COLLECTION\"," + admin() + ",\"database\":\"" + auditDb + "\",\"collection\":\"" + auditCol + "\"}");

            String wRes = rpc(out, in, "{\"requestId\":\"a_w1\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"" + auditDb + "\",\"collection\":\"" + auditCol + "\",\"document\":{\"ad\":\"Laptop\",\"fiyat\":1500}}");
            check("WRITE succeeds for audit test", wRes.contains("\"status\":\"OK\""));

            // 2. Audit loglarını listele ve recordCreated kaydını doğrula
            String logsRes = rpc(out, in, "{\"requestId\":\"a_l1\",\"action\":\"AUDIT_LOGS\"," + admin() + ",\"filter\":{\"action\":\"recordCreated\"}}");
            check("Audit log records recordCreated action", logsRes.contains("\"action\":\"recordCreated\"") && logsRes.contains(auditCol));

            // 3. Kaydı güncelle (UPDATE -> Audit log oluşmalı)
            String uRes = rpc(out, in, "{\"requestId\":\"a_u1\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + auditDb + "\",\"collection\":\"" + auditCol + "\",\"filter\":{\"ad\":\"Laptop\"},\"document\":{\"fiyat\":2000}}");
            check("UPDATE succeeds for audit test", uRes.contains("\"status\":\"OK\""));

            // Güncelleme logunu çek
            String uLogRes = rpc(out, in, "{\"requestId\":\"a_l2\",\"action\":\"AUDIT_LOGS\"," + admin() + ",\"filter\":{\"action\":\"recordUpdated\"}}");
            check("Audit log records recordUpdated action", uLogRes.contains("\"action\":\"recordUpdated\"") && uLogRes.contains("2000"));

            // 4. Güncelleme logunun gerçek ID'sini JSON parser ile parse ederek çek
            Map<String, Object> parsedRes = jsonparser.Json.parseObject(uLogRes);
            List<?> dataList = (List<?>) parsedRes.get("data");
            Map<?, ?> firstLog = (Map<?, ?>) dataList.get(0);
            String logId = String.valueOf(firstLog.get("id"));

            String revRes = rpc(out, in, "{\"requestId\":\"a_rev1\",\"action\":\"REVERT_AUDIT_LOG\"," + admin()
                    + ",\"filter\":{\"logId\":\"" + logId + "\"}}");
            check("REVERT_AUDIT_LOG for UPDATE succeeds", revRes.contains("\"status\":\"OK\""));

            // Kaydın fiyatı gerçekten 1500'e döndü mü kontrol et
            String readAfterRev = rpc(out, in, "{\"requestId\":\"a_r1\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"" + auditDb + "\",\"collection\":\"" + auditCol + "\",\"filter\":{\"ad\":\"Laptop\"}}");
            check("Record reverted back to old values", readAfterRev.contains("1500") && !readAfterRev.contains("2000"));

            // Aynı logu 2. kez revert etmeyi dene -> ERROR vermeli
            String doubleRev = rpc(out, in, "{\"requestId\":\"a_rev2\",\"action\":\"REVERT_AUDIT_LOG\"," + admin()
                    + ",\"filter\":{\"logId\":\"" + logId + "\"}}");
            check("Double revert is rejected", doubleRev.contains("\"status\":\"ERROR\"") && doubleRev.contains("already been reverted"));

            // 5. Test veritabanını temizle
            rpc(out, in, "{\"requestId\":\"a_drop\",\"action\":\"DROP_DATABASE\"," + admin() + ",\"database\":\"" + auditDb + "\"}");
            String acts = rpc(out, in, "{\"requestId\":\"a3\",\"action\":\"RECENT_ACTIVITIES\"," + admin()
                    + ",\"filter\":{\"limit\":5}}");
            check("RECENT_ACTIVITIES returns activities", acts.contains("occurredAt"));

            section("Data format validation (Ister_0014)");
            String badType = rpc(out, in, "{\"requestId\":\"v1\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"" + TEST_DB + "\",\"collection\":\"test_kayitlar\","
                    + "\"document\":{\"adet\":\"metin-olmamali\"}}");
            check("wrong field type is rejected", badType.contains("must be of type int"));

            String good = rpc(out, in, "{\"requestId\":\"v2\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"" + TEST_DB + "\",\"collection\":\"test_kayitlar\","
                    + "\"document\":{\"baslik\":\"uc\",\"adet\":3}}");
            check("correct field types accepted", good.contains("1 record inserted"));

            String free = rpc(out, in, "{\"requestId\":\"v3\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"semasiz\",\"collection\":\"serbest\","
                    + "\"document\":{\"herhangi\":\"deger\"}}");
            check("collections without schema stay free", free.contains("1 record inserted"));

            section("Field (feature) type definitions");
            rpc(out, in, "{\"requestId\":\"d0\",\"action\":\"CREATE_DATABASE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"document\":{}}");

            String cc2 = rpc(out, in, "{\"requestId\":\"d1\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"fields\":[{\"name\":\"oda\",\"type\":\"int\"},"
                    + "{\"name\":\"ad\",\"type\":\"string\"}]}}");
            check("CREATE_COLLECTION accepts field types", cc2.contains("\"type\":\"int\""));

            String df = rpc(out, in, "{\"requestId\":\"d2\",\"action\":\"DEFINE_FIELDS\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"fields\":[{\"name\":\"aktif\",\"type\":\"boolean\"}]}}");
            check("DEFINE_FIELDS adds a new field", df.contains("aktif"));
            check("existing fields are preserved", df.contains("oda") && df.contains("ad"));

            String single = rpc(out, in, "{\"requestId\":\"d3\",\"action\":\"DEFINE_FIELDS\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"name\":\"metrekare\",\"type\":\"double\"}}");
            check("single field form works", single.contains("metrekare"));

            String okWrite = rpc(out, in, "{\"requestId\":\"d4\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"oda\":3,\"ad\":\"Salon\",\"aktif\":true}}");
            check("matching types accepted", okWrite.contains("1 record inserted"));

            String badWrite = rpc(out, in, "{\"requestId\":\"d5\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\",\"document\":{\"oda\":\"5\"}}");
            check("mismatching type rejected", badWrite.contains("must be of type int"));

            String retype = rpc(out, in, "{\"requestId\":\"d6\",\"action\":\"DEFINE_FIELDS\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"name\":\"oda\",\"type\":\"string\"}}");
            check("field type can be corrected", retype.contains("\"status\":\"OK\""));
            String afterRetype = rpc(out, in, "{\"requestId\":\"d7\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\",\"document\":{\"oda\":\"5\"}}");
            check("new type takes effect", afterRetype.contains("1 record inserted"));

            String delField = rpc(out, in, "{\"requestId\":\"d8\",\"action\":\"DELETE_FIELD\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\",\"document\":{\"name\":\"metrekare\"}}");
            check("DELETE_FIELD removes definition", !delField.contains("metrekare\",\"type"));

            String badFieldType = rpc(out, in, "{\"requestId\":\"d9\",\"action\":\"DEFINE_FIELDS\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"odalar\","
                    + "\"document\":{\"name\":\"x\",\"type\":\"sayisal\"}}");
            check("unsupported type rejected", badFieldType.contains("Unsupported field type"));

            section("Automatic type learning");
            String firstWrite = rpc(out, in, "{\"requestId\":\"e1\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"serbest\","
                    + "\"document\":{\"sicaklik\":22}}");
            check("first write succeeds", firstWrite.contains("1 record inserted"));

            String learned = rpc(out, in, "{\"requestId\":\"e2\",\"action\":\"DESCRIBE_COLLECTION\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"serbest\"}");
            check("type is learned from first write", learned.contains("\"sicaklik\"")
                    && learned.contains("\"int\""));
            check("learned fields are marked", learned.contains("\"inferred\":true"));

            String conflict = rpc(out, in, "{\"requestId\":\"e3\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"tipli_db\",\"collection\":\"serbest\","
                    + "\"document\":{\"sicaklik\":\"yirmiiki\"}}");
            check("conflicting type rejected afterwards", conflict.contains("must be of type int"));

            rpc(out, in, "{\"requestId\":\"e4\",\"action\":\"DROP_DATABASE\"," + admin()
                    + ",\"database\":\"tipli_db\"}");

            section("SYSTEM_STATUS and DELETE_COLLECTION");
            String ss = rpc(out, in, "{\"requestId\":\"s1\",\"action\":\"SYSTEM_STATUS\"," + admin() + "}");
            check("SYSTEM_STATUS reports mongo state", ss.contains("isMongoConnected"));
            check("SYSTEM_STATUS reports api state", ss.contains("isApiOnline"));

            rpc(out, in, "{\"requestId\":\"s2\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"" + TEST_DB + "\",\"collection\":\"silinecek\"}");
            String dc = rpc(out, in, "{\"requestId\":\"s3\",\"action\":\"DELETE_COLLECTION\"," + admin()
                    + ",\"database\":\"" + TEST_DB + "\",\"collection\":\"silinecek\"}");
            check("DELETE_COLLECTION works as alias", dc.contains("Collection dropped"));

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

            section("WRITE Security Tests");

            // 1. Boş doküman ile ekleme denemesi -> ERROR dönmeli
            String emptyWrite = rpc(out, in, "{\"requestId\":\"w_empty\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{}}");
            check("WRITE with empty document is rejected", emptyWrite.contains("\"status\":\"ERROR\"")
                    && emptyWrite.contains("non-empty object"));

            // 2. Elle 'id' gönderme denemesi -> ERROR dönmeli
            String fakeIdWrite = rpc(out, in, "{\"requestId\":\"w_id\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"id\":\"rec-fake\",\"ad\":\"Test\"}}");
            check("WRITE with custom 'id' is rejected", fakeIdWrite.contains("\"status\":\"ERROR\"")
                    && fakeIdWrite.contains("cannot be provided"));

            // 3. Elle 'createdAt' gönderme denemesi -> ERROR dönmeli
            String fakeDateWrite = rpc(out, in, "{\"requestId\":\"w_date\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"createdAt\":\"2020-01-01\",\"ad\":\"Test\"}}");
            check("WRITE with custom 'createdAt' is rejected", fakeDateWrite.contains("\"status\":\"ERROR\"")
                    && fakeDateWrite.contains("cannot be provided"));

String dollarKeyWrite = rpc(out, in, "{\"requestId\":\"w_dollar\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"$gizli\":123}}");
check("WRITE with '$' key prefix is rejected", dollarKeyWrite.contains("\"status\":\"ERROR\"")
        && (dollarKeyWrite.contains("Dangerous query operator") || dollarKeyWrite.contains("Invalid document structure")));
            section("WRITE & Server Timestamp Tests");

// 1. Boş doküman reddedilmeli
String wEmpty = rpc(out, in, "{\"requestId\":\"w1\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{}}");
check("WRITE with empty document is rejected", wEmpty.contains("\"status\":\"ERROR\""));

// 2. Elle id gönderimi reddedilmeli
String wId = rpc(out, in, "{\"requestId\":\"w2\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"id\":\"custom-1\",\"ad\":\"Test\"}}");
check("WRITE with custom id is rejected", wId.contains("\"status\":\"ERROR\"") && wId.contains("Field 'id' is generated"));

// 3. Elle createdAt / updatedAt gönderimi reddedilmeli
String wDate = rpc(out, in, "{\"requestId\":\"w3\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"createdAt\":\"2020-01-01\",\"ad\":\"Test\"}}");
check("WRITE with custom createdAt is rejected", wDate.contains("\"status\":\"ERROR\""));

String wUpDate = rpc(out, in, "{\"requestId\":\"w4\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"updatedAt\":\"2020-01-01\",\"ad\":\"Test\"}}");
check("WRITE with custom updatedAt is rejected", wUpDate.contains("\"status\":\"ERROR\""));

// 4. İç içe (nested) $ anahtarı reddedilmeli
String wNestedDollar = rpc(out, in, "{\"requestId\":\"w5\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Test\",\"meta\":{\"$hack\":1}}}");
check("WRITE with nested $ key is rejected", wNestedDollar.contains("\"status\":\"ERROR\""));

// 5. Başarılı eklemede otomatik createdAt ve updatedAt oluşmalı
String wSuccess = rpc(out, in, "{\"requestId\":\"w6\",\"action\":\"WRITE\"," + admin()
        + ",\"database\":\"okul\",\"collection\":\"ogrenciler\",\"document\":{\"ad\":\"Ahmet\",\"sinif\":2}}");
check("WRITE generates automatic createdAt", wSuccess.contains("\"createdAt\":\""));
check("WRITE generates automatic updatedAt", wSuccess.contains("\"updatedAt\":\""));

                        // ============================================================
            // UPDATE SECURITY TESTS
            // ============================================================

            section("UPDATE Security Tests");

            // ------------------------------------------------------------
            // UPDATE testleri icin temiz bir collection olustur
            // ------------------------------------------------------------
            String updateTestDb = "update_security_db";
            String updateTestCol = "records";

            String createUpdateTestDb = rpc(out, in,
                    "{\"requestId\":\"upd-setup-db\",\"action\":\"CREATE_DATABASE\"," + admin()
                    + ",\"database\":\"" + updateTestDb + "\"}");

            String createUpdateTestCollection = rpc(out, in,
                    "{\"requestId\":\"upd-setup-col\",\"action\":\"CREATE_COLLECTION\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"document\":{\"fields\":["
                    + "{\"name\":\"name\",\"type\":\"string\"},"
                    + "{\"name\":\"age\",\"type\":\"int\"}"
                    + "]}}");

            // Iki test kaydi
            String updateSeed1 = rpc(out, in,
                    "{\"requestId\":\"upd-seed-1\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"document\":{\"name\":\"Ali\",\"age\":20}}");

            String updateSeed2 = rpc(out, in,
                    "{\"requestId\":\"upd-seed-2\",\"action\":\"WRITE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"document\":{\"name\":\"Ayse\",\"age\":25}}");

            // ------------------------------------------------------------
            // 1. UPDATE without filter
            // ------------------------------------------------------------
            String updateNoFilter = rpc(out, in,
                    "{\"requestId\":\"upd-sec-1\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"document\":{\"name\":\"HACK\"}}");

            check("UPDATE without filter is rejected",
                    updateNoFilter.contains("\"status\":\"ERROR\"")
                    && updateNoFilter.contains("Filter is required for UPDATE"));


            // ------------------------------------------------------------
            // 2. UPDATE with empty filter
            // ------------------------------------------------------------
            String updateEmptyFilter = rpc(out, in,
                    "{\"requestId\":\"upd-sec-2\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{},\"document\":{\"name\":\"HACK\"}}");

            check("UPDATE with empty filter is rejected",
                    updateEmptyFilter.contains("\"status\":\"ERROR\"")
                    && updateEmptyFilter.contains("Mass update with empty filter is not allowed"));


            // ------------------------------------------------------------
            // 3. UPDATE with $where injection
            // ------------------------------------------------------------
            String updateWhereInjection = rpc(out, in,
                    "{\"requestId\":\"upd-sec-3\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"$where\":\"this.age == 20\"},"
                    + "\"document\":{\"name\":\"HACK\"}}");

            check("UPDATE rejects $where injection",
                    updateWhereInjection.contains("\"status\":\"ERROR\"")
                    && updateWhereInjection.contains("Dangerous query operator"));


            // ------------------------------------------------------------
            // 4. UPDATE with nested $ operator injection
            // ------------------------------------------------------------
            String updateNestedInjection = rpc(out, in,
                    "{\"requestId\":\"upd-sec-4\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"age\":{\"nested\":{\"$ne\":20}}},"
                    + "\"document\":{\"name\":\"HACK\"}}");

            check("UPDATE rejects nested $ operator injection",
                    updateNestedInjection.contains("\"status\":\"ERROR\"")
                    && updateNestedInjection.contains("Dangerous query operator"));


            // ------------------------------------------------------------
            // 5. UPDATE rejects invalid schema type
            // ------------------------------------------------------------
            String updateBadType = rpc(out, in,
                    "{\"requestId\":\"upd-sec-5\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"Ali\"},"
                    + "\"document\":{\"age\":\"twenty\"}}");

            check("UPDATE rejects invalid schema type",
                    updateBadType.contains("\"status\":\"ERROR\"")
                    && updateBadType.contains("must be of type"));


            // ------------------------------------------------------------
            // 6. Valid UPDATE succeeds
            // ------------------------------------------------------------
            String updateValid = rpc(out, in,
                    "{\"requestId\":\"upd-sec-6\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"Ali\"},"
                    + "\"document\":{\"age\":21}}");

            check("valid UPDATE succeeds",
                    updateValid.contains("\"status\":\"OK\"")
                    && updateValid.contains("1 record(s) updated"));


            // ------------------------------------------------------------
            // 7. Updated value can be read back
            // ------------------------------------------------------------
            String updateVerify = rpc(out, in,
                    "{\"requestId\":\"upd-sec-7\",\"action\":\"READ\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"Ali\"}}");

            check("updated record contains new value",
                    updateVerify.contains("\"age\":21"));


            // ------------------------------------------------------------
            // 8. Immutable id cannot be changed
            // ------------------------------------------------------------
            String updateIdChange = rpc(out, in,
                    "{\"requestId\":\"upd-sec-8\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"Ali\"},"
                    + "\"document\":{\"id\":\"fake-id\"}}");

            check("UPDATE cannot change immutable id",
                    updateIdChange.contains("\"status\":\"ERROR\"")
                    && updateIdChange.contains("cannot be updated"));


            // ------------------------------------------------------------
            // 9. Immutable createdAt cannot be changed
            // ------------------------------------------------------------
            String updateCreatedAtChange = rpc(out, in,
                    "{\"requestId\":\"upd-sec-9\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"Ali\"},"
                    + "\"document\":{\"createdAt\":\"1999-01-01\"}}");

            check("UPDATE cannot change immutable createdAt",
                    updateCreatedAtChange.contains("\"status\":\"ERROR\"")
                    && updateCreatedAtChange.contains("cannot be updated"));


            // ------------------------------------------------------------
            // 10. UPDATE with no matching records
            // ------------------------------------------------------------
            String updateNoMatch = rpc(out, in,
                    "{\"requestId\":\"upd-sec-10\",\"action\":\"UPDATE\"," + admin()
                    + ",\"database\":\"" + updateTestDb
                    + "\",\"collection\":\"" + updateTestCol
                    + "\",\"filter\":{\"name\":\"DoesNotExist\"},"
                    + "\"document\":{\"age\":99}}");

            check("UPDATE with no matching records returns 0",
                    updateNoMatch.contains("\"status\":\"OK\"")
                    && updateNoMatch.contains("0 record(s) updated"));


            // ------------------------------------------------------------
            // UPDATE test veritabanini temizle
            // ------------------------------------------------------------
            String dropUpdateTestDb = rpc(out, in,
                    "{\"requestId\":\"upd-cleanup\",\"action\":\"DROP_DATABASE\"," + admin()
                    + ",\"database\":\"" + updateTestDb + "\"}");

            check("UPDATE security test database cleanup",
                    dropUpdateTestDb.contains("\"status\":\"OK\""));
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

    /**
     * Ister_0011/0012 testleri icin talep klasorune ornek dosyalar yazar.
     * Sunucu ile ayni makinede calisildigi varsayilir.
     */
    static void prepareRequestFiles() throws Exception {
        String dir = System.getenv("REQUEST_DIR");
        if (dir == null || dir.isBlank()) dir = "db-requests";
        java.nio.file.Path base = java.nio.file.Paths.get(dir);
        java.nio.file.Files.createDirectories(base);

        String content = "{"
                + "\"database\":\"" + TEST_DB + "\","
                + "\"department\":\"Test\","
                + "\"description\":\"protokol testi\","
                + "\"collections\":[{"
                + "  \"name\":\"test_kayitlar\","
                + "  \"fields\":[{\"name\":\"baslik\",\"type\":\"string\"},"
                + "             {\"name\":\"adet\",\"type\":\"int\"}],"
                + "  \"records\":[{\"baslik\":\"bir\",\"adet\":1},"
                + "              {\"baslik\":\"iki\",\"adet\":2}]"
                + "}]}";

        java.nio.file.Files.write(base.resolve(TEST_FILE),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(base.resolve(BROKEN_FILE),
                "{ bu bozuk".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Cevaptan bir alanin ilk degerini cikarir (basit metin arama). */
    static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        int start = i + key.length() + 4;
        return json.substring(start, json.indexOf('"', start));
    }

    static void check(String name, boolean cond) {
        if (cond) { ok++; System.out.println("  [OK]   " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }
}
