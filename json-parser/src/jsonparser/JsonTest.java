package jsonparser;

import java.util.List;
import java.util.Map;

/**
 * JSON PARSER'IN BAĞIMSIZ TESTİ.
 *
 * Bu proje TCP sunucusundan tamamen habersizdir; parser'ı tek başına
 * geliştirmek ve test etmek için bu sınıfı çalıştırmak yeterlidir:
 *
 *   javac -d out src/jsonparser/*.java
 *   java -cp out jsonparser.JsonTest
 *
 * Tüm testler geçerse "TÜM TESTLER GEÇTİ" yazar ve 0 ile çıkar;
 * herhangi biri kalırsa hangi test olduğunu söyler ve 1 ile çıkar.
 */
public class JsonTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // --- Temel tipler ---
        assertEquals("string parse", Json.parse("\"hello\""), "hello");
        assertEquals("integer parse", Json.parse("42"), 42L);
        assertEquals("negative number parse", Json.parse("-7"), -7L);
        assertEquals("decimal parse", Json.parse("3.14"), 3.14);
        assertEquals("true parse", Json.parse("true"), Boolean.TRUE);
        assertEquals("false parse", Json.parse("false"), Boolean.FALSE);
        assertEquals("null parse", Json.parse("null"), null);

        // --- Türkçe karakterler ve kaçış dizileri ---
        assertEquals("Turkish characters", Json.parse("\"Ayşe Yılmaz İstanbul'da\""), "Ayşe Yılmaz İstanbul'da");
        assertEquals("escape sequences", Json.parse("\"line\\ntab\\tquote\\\"\""), "line\ntab\tquote\"");
        assertEquals("unicode escape", Json.parse("\"\\u0130stanbul\""), "İstanbul");

        // --- Object ve array ---
        Map<String, Object> obj = Json.parseObject("{\"name\":\"Ali\",\"age\":30,\"active\":true}");
        assertEquals("object field: name", obj.get("name"), "Ali");
        assertEquals("object field: age", obj.get("age"), 30L);
        assertEquals("object field: active", obj.get("active"), Boolean.TRUE);

        Object array = Json.parse("[1,\"two\",false,null]");
        assertTrue("array type", array instanceof List);
        assertEquals("array size", (long) ((List<?>) array).size(), 4L);

        // --- İç içe yapılar (frontend'in gerçek mesajlarına benzer) ---
        String request = "{\"action\":\"insert\",\"collection\":\"users\","
                + "\"data\":{\"name\":\"Zeynep\",\"departments\":[\"Sensor\",\"Signal\"]}}";
        Map<String, Object> m = Json.parseObject(request);
        assertEquals("nested: action", m.get("action"), "insert");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) m.get("data");
        assertTrue("nested: departments array", data.get("departments") instanceof List);

        // --- Gidiş-dönüş (parse -> stringify -> parse aynı sonucu vermeli) ---
        String roundTrip = Json.stringify(m);
        assertEquals("round trip", Json.parseObject(roundTrip), m);

        // --- Hatalı girdiler: parser ÇÖKMEMELİ, JsonException fırlatmalı ---
        expectError("malformed input", "abc{");
        expectError("unclosed object", "{\"a\":1");
        expectError("unclosed string", "\"half");
        expectError("trailing characters", "{} extra");
        expectError("invalid escape", "\"\\x\"");

        // --- Sağlamlık: sınır durumları (stres testinden gelen kalıcı testler) ---
        assertEquals("scientific notation", Json.parse("1.5e3"), 1500.0);
        assertTrue("negative exponent number", ((Double) Json.parse("-2.3E-4")) < 0);
        assertTrue("number beyond Long falls back to Double",
                Json.parse("123456789012345678901234567890") instanceof Double);
        assertEquals("multi-line / whitespaced JSON",
                Json.parseObject("{\n  \"name\" : \"Ali\" ,\n  \"age\" : 30\n}").get("name"), "Ali");
        assertTrue("emoji (surrogate pair) preserved",
                "\uD83D\uDE00".equals(Json.parse("\"\\uD83D\\uDE00\"")));
        assertEquals("duplicate key: last value wins",
                Json.parseObject("{\"a\":1,\"a\":2}").get("a"), 2L);

        expectError("null input", null);
        expectError("empty input", "");
        expectError("whitespace only", "   ");
        expectError("leading zero (01)", "01");
        expectError("lone minus sign", "-");
        expectError("trailing comma", "[1,2,]");
        expectError("malformed number 1.2.3", "1.2.3");
        expectError("excessively deep JSON", "[".repeat(500) + "]".repeat(500));

        // --- Sonuç ---
        System.out.println("\nResult: " + passed + " passed, " + failed + " failed.");
        if (failed == 0) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.exit(1);
        }
    }

    private static void assertEquals(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        report(name, ok, ok ? null : "expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(String name, boolean condition) {
        report(name, condition, condition ? null : "condition not met");
    }

    private static void expectError(String name, String malformedJson) {
        try {
            Json.parse(malformedJson);
            report(name, false, "expected an error but parse succeeded");
        } catch (Json.JsonException e) {
            report(name, true, null); // beklenen davranış: kontrollü hata
        }
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("[OK]   " + name); }
        else    { failed++; System.out.println("[FAIL] " + name + " -> " + detail); }
    }
}
