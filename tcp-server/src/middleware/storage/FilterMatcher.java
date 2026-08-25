package middleware.storage;

import java.util.Map;
import java.util.Objects;

public class FilterMatcher {

    @SuppressWarnings("unchecked")
    public static boolean matches(Map<String, Object> record, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (record == null) return false;

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            Object actual = record.get(key);

            if (expected instanceof Map<?, ?> opMap) {
                if (!matchesOperators(actual, (Map<String, Object>) opMap)) {
                    return false;
                }
            } else {
                if (!Objects.equals(actual, expected)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesOperators(Object actual, Map<String, Object> ops) {
        for (Map.Entry<String, Object> opEntry : ops.entrySet()) {
            String op = String.valueOf(opEntry.getKey()).toLowerCase().trim();
            Object targetVal = opEntry.getValue();

            switch (op) {
                case ">", "gt" -> {
                    if (compare(actual, targetVal) <= 0) return false;
                }
                case ">=", "gte" -> {
                    if (compare(actual, targetVal) < 0) return false;
                }
                case "<", "lt" -> {
                    if (compare(actual, targetVal) >= 0) return false;
                }
                case "<=", "lte" -> {
                    if (compare(actual, targetVal) > 0) return false;
                }
                case "!=", "ne" -> {
                    if (Objects.equals(actual, targetVal)) return false;
                }
                case "like", "contains" -> {
                    if (actual == null || targetVal == null) return false;
                    String actualStr = String.valueOf(actual).toLowerCase();
                    String targetStr = String.valueOf(targetVal).toLowerCase();
                    if (!actualStr.contains(targetStr)) return false;
                }
                default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
            }
        }
        return true;
    }

    private static int compare(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable && b != null && a.getClass().isAssignableFrom(b.getClass())) {
            return ((Comparable<Object>) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}