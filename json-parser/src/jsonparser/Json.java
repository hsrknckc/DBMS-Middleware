package jsonparser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sıfırdan yazılmış minimal JSON parser + serializer.
 *
 * Veri modeli (JSON tipi -> Java tipi):
 *   object  -> Map<String, Object>
 *   array   -> List<Object>
 *   string  -> String
 *   number  -> Long (tam sayı ise) veya Double
 *   true    -> Boolean.TRUE
 *   false   -> Boolean.FALSE
 *   null    -> null
 *
 * Kullanım:
 *   Map<String,Object> obj = Json.parseObject("{\"a\":1}");
 *   String s = Json.stringify(obj);
 */
public final class Json {

    private Json() {} // yardımcı sınıf, nesnesi oluşturulmaz

    // ------------------------------------------------------------
    // PARSE (String -> Java nesnesi)
    // ------------------------------------------------------------

    /** Bir JSON yapısının izin verilen en fazla iç içe geçme derinliği.
     *  Aşırı derin girdiler JVM yığınını taşırmasın diye sınırlıdır:
     *  StackOverflowError bir Error'dur ve normal catch(Exception) ile
     *  yakalanamaz; bu yüzden derinliği ÖNCEDEN kontrol ediyoruz. */
    public static final int MAX_DEPTH = 200;

    /** Herhangi bir JSON değerini parse eder. */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Text to parse is null");
        }
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.isAtEnd()) {
            throw new JsonException("Extra characters after end of JSON (position " + p.pos + ")");
        }
        return value;
    }

    /** Kök elemanın JSON object olmasını bekler, değilse hata fırlatır. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("Expected a JSON object, got: " + typeName(v));
        }
        return (Map<String, Object>) v;
    }

    /** Parser'ın iç durumu: metin + o an bakılan pozisyon. */
    private static final class Parser {
        final String s;
        int pos = 0;
        int depth = 0; // o an kaç seviye iç içeyiz

        Parser(String s) {
            this.s = s;
        }

        boolean isAtEnd() {
            return pos >= s.length();
        }

        char peek() {
            if (isAtEnd()) throw new JsonException("Unexpected end of JSON (position " + pos + ")");
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            char g = next();
            if (g != c) {
                throw new JsonException("'" + c + "' expected but got '" + g + "' (position " + (pos - 1) + ")");
            }
        }

        void increaseDepth() {
            depth++;
            if (depth > MAX_DEPTH) {
                throw new JsonException("JSON is nested too deeply (max "
                        + MAX_DEPTH + " levels) - position " + pos);
            }
        }

        void skipWhitespace() {
            while (!isAtEnd()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        /** Bir JSON değeri parse eder: ilk karaktere bakıp tipe karar verir. */
        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': return parseLiteral("true", Boolean.TRUE);
                case 'f': return parseLiteral("false", Boolean.FALSE);
                case 'n': return parseLiteral("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw new JsonException("Unexpected character: '" + c + "' (position " + pos + ")");
            }
        }

        Map<String, Object> parseObject() {
            increaseDepth();
            Map<String, Object> map = new LinkedHashMap<>(); // ekleme sırasını korur
            expect('{');
            skipWhitespace();
            if (peek() == '}') { // boş object: {}
                next();
                depth--;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();      // anahtar her zaman string'dir
                skipWhitespace();
                expect(':');
                Object value = parseValue();     // değer herhangi bir JSON tipi olabilir
                map.put(key, value);
                skipWhitespace();
                char c = next();
                if (c == ',') continue;          // sıradaki anahtar-değer çifti
                if (c == '}') break;             // object bitti
                throw new JsonException("',' or '}' expected but got '" + c + "' (position " + (pos - 1) + ")");
            }
            depth--;
            return map;
        }

        List<Object> parseArray() {
            increaseDepth();
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { // boş dizi: []
                next();
                depth--;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') continue;
                if (c == ']') break;
                throw new JsonException("',' or ']' expected but got '" + c + "' (position " + (pos - 1) + ")");
            }
            depth--;
            return list;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;                     // string bitti
                if (c == '\\') {                          // kaçış dizisi
                    char esc = next();
                    switch (esc) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u': // u ile gelen 4 haneli hex unicode karakter (Türkçe karakterler böyle de gelebilir)
                            if (pos + 4 > s.length()) throw new JsonException("Incomplete \\u escape sequence");
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonException("Invalid \\u escape sequence: " + hex);
                            }
                            break;
                        default:
                            throw new JsonException("Invalid escape character: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') next();
            while (!isAtEnd()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
                else break;
            }
            String num = s.substring(start, pos);

            // JSON standardı baştaki sıfıra izin vermez: "01" geçersizdir.
            String digits = num.startsWith("-") ? num.substring(1) : num;
            if (digits.length() > 1 && digits.charAt(0) == '0'
                    && digits.charAt(1) != '.' && digits.charAt(1) != 'e'
                    && digits.charAt(1) != 'E') {
                throw new JsonException("Number cannot start with a leading zero: " + num);
            }

            try {
                // Nokta/üs yoksa tam sayı olarak sakla (id, sayaç vb. için daha doğal)
                if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(num);
                    } catch (NumberFormatException overflow) {
                        // Long'a sığmayacak kadar büyük: veriyi KAYBETMEK yerine
                        // ondalık olarak sakla (reddetmek istemciyi bloke ederdi).
                        return Double.parseDouble(num);
                    }
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number: " + num);
            }
        }

        Object parseLiteral(String literal, Object value) {
            if (pos + literal.length() > s.length()
                    || !s.startsWith(literal, pos)) {
                throw new JsonException("'" + literal + "' expected (position " + pos + ")");
            }
            pos += literal.length();
            return value;
        }
    }

    // ------------------------------------------------------------
    // STRINGIFY (Java nesnesi -> String)
    // ------------------------------------------------------------

    /** Java nesnesini tek satırlık JSON metnine çevirir. */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String str) {
            writeString(sb, str);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new JsonException("JSON does not support NaN/Infinity");
            }
            sb.append(d);
        } else if (value instanceof Number) { // diğer sayı tipleri (BigDecimal vb.)
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                write(sb, list.get(i));
            }
            sb.append(']');
        } else {
            throw new JsonException("Type cannot be serialized to JSON: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) { // diğer kontrol karakterleri unicode kaçışı olarak yazılır
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c); // Türkçe karakterler UTF-8 ile olduğu gibi gider
                    }
            }
        }
        sb.append('"');
    }

    private static String typeName(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    /** JSON hataları için özel exception - bozuk mesajı yakalayıp istemciye düzgün hata dönebilelim diye. */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
