package middleware.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VERI FORMAT DOGRULAMASI (Ister_0014)
 *
 * "Disaridan gelen veritabani islem taleplerinin iceriginde, uygun veri
 * formatinda veri olup olmadiginin kontrolunu yapacaktir."
 *
 * Bir koleksiyon icin alan tanimlari kaydedilmisse (IMPORT_FILE ile gelen
 * "fields" listesi), gelen kayitlarin bu tanimlara uymasi beklenir.
 * Uymayan istek reddedilir; boylece ayni alan bazi kayitlarda sayi, bazi
 * kayitlarda metin olarak saklanmaz.
 *
 * Sema tanimlanmamis koleksiyonlar serbesttir — MongoDB semasiz calisir
 * ve her koleksiyon icin sema zorunlu tutulmaz.
 *
 * DESTEKLENEN TIPLER
 *   string   metin
 *   int      tam sayi
 *   double   ondalik
 *   boolean  dogru/yanlis
 *   array    dizi
 *   object   ic ice nesne
 *   any      kontrol edilmez
 */
public class SchemaValidator {

    /**
     * Gelen belgeyi alan tanimlarina gore dogrular.
     *
     * @param fields   alan tanimlari: [{"name":"oda","type":"int"}, ...]
     * @param document dogrulanacak kayit
     * @return bos liste = gecerli; doluysa her eleman bir hata mesaji
     */
    @SuppressWarnings("unchecked")
    public static List<String> validate(List<?> fields, Map<String, Object> document) {
        List<String> errors = new ArrayList<>();
        if (fields == null || fields.isEmpty() || document == null) return errors;

        for (Object f : fields) {
            if (!(f instanceof Map)) continue;
            Map<String, Object> field = (Map<String, Object>) f;

            String name = str(field.get("name"));
            String type = str(field.get("type"));
            if (name == null || type == null) continue;

            // Alan gonderilmemisse kontrol edilmez; kismi guncellemeler serbest.
            if (!document.containsKey(name)) continue;

            Object value = document.get(name);
            if (value == null) continue;   // null her tip icin kabul edilir

            if (!matches(type, value)) {
                errors.add("Field '" + name + "' must be of type " + type
                        + " but got " + describe(value));
            }
        }
        return errors;
    }

    /** Degerin beklenen tipe uyup uymadigi. */
    private static boolean matches(String type, Object value) {
        return switch (type.toLowerCase()) {
            case "string", "text" -> value instanceof String;
            case "int", "integer", "long" -> value instanceof Long || value instanceof Integer;
            case "double", "float", "number", "decimal" ->
                    value instanceof Double || value instanceof Long || value instanceof Integer;
            case "boolean", "bool" -> value instanceof Boolean;
            case "array", "list" -> value instanceof List;
            case "object", "map" -> value instanceof Map;
            default -> true;   // "any" ve bilinmeyen tipler: kontrol edilmez
        };
    }

    /** Hata mesajinda gosterilecek okunabilir tip adi. */
    private static String describe(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Long || value instanceof Integer) return "int";
        if (value instanceof Double) return "double";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return value.getClass().getSimpleName();
    }

    private static String str(Object o) {
        return (o instanceof String s && !s.isBlank()) ? s : null;
    }
}
