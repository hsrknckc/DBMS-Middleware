package middleware.validation;

import java.util.Map;

public class FilterSanitizer {

    /**
     * Filtre nesnesini derinlemesine tarar.
     * $ ile baslayan operator enjeksiyonlarini tespit ederse hata firlatir.
     */
    @SuppressWarnings("unchecked")
    public static void validate(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();

            // 1. $ ile baslayan operator enjeksiyonu kontrolu ($where, $regex, $ne vb.)
            if (key != null && key.startsWith("$")) {
                throw new IllegalArgumentException("Dangerous query operator is forbidden: " + key);
            }

            // 2. Ic ice (nested) map yapisi varsa onu da tara
            if (entry.getValue() instanceof Map<?, ?> innerMap) {
                validate((Map<String, Object>) innerMap);
            }
        }
    }
}