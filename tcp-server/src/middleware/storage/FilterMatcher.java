package middleware.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class FilterMatcher {

    private static final int MAX_IN_CLAUSE_SIZE = 100;

    public static boolean matches(Map<String, Object> record, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return true;
        if (record == null) return false;

        for (Map.Entry<String, Object> condition : filter.entrySet()) {
            String key = condition.getKey();
            Object actual = record.get(key);
            Object expected = condition.getValue();

            if (expected instanceof Map<?, ?> opMap) {
                if (!matchesOperators(actual, castOpMap(opMap))) {
                    return false;
                }
            } else if (!looselyEquals(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castOpMap(Map<?, ?> opMap) {
        return (Map<String, Object>) opMap;
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
                    if (looselyEquals(actual, targetVal)) return false;
                }
                case "like", "contains" -> {
                    if (actual == null || targetVal == null) return false;
                    String actualStr = String.valueOf(actual).toLowerCase();
                    String targetStr = String.valueOf(targetVal).toLowerCase();
                    if (!actualStr.contains(targetStr)) return false;
                }
                case "in" -> {
                    if (!(targetVal instanceof List<?> list)) {
                        throw new IllegalArgumentException("'in' operator requires a list of values");
                    }
                    if (list.size() > MAX_IN_CLAUSE_SIZE) {
                        throw new IllegalArgumentException("'in' operator list exceeds max size: " + MAX_IN_CLAUSE_SIZE);
                    }
                    boolean found = false;
                    for (Object item : list) {
                        if (looselyEquals(actual, item)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) return false;
                }
                case "nin", "not_in" -> {
                    if (!(targetVal instanceof List<?> list)) {
                        throw new IllegalArgumentException("'nin' operator requires a list of values");
                    }
                    if (list.size() > MAX_IN_CLAUSE_SIZE) {
                        throw new IllegalArgumentException("'nin' operator list exceeds max size: " + MAX_IN_CLAUSE_SIZE);
                    }
                    for (Object item : list) {
                        if (looselyEquals(actual, item)) return false;
                    }
                }
                case "between" -> {
                    if (!(targetVal instanceof List<?> list) || list.size() != 2) {
                        throw new IllegalArgumentException("'between' operator requires an array of exactly 2 values: [min, max]");
                    }
                    Object min = list.get(0);
                    Object max = list.get(1);
                    if (compare(min, max) > 0) {
                        throw new IllegalArgumentException("'between' min value cannot be greater than max value");
                    }
                    if (compare(actual, min) < 0 || compare(actual, max) > 0) {
                        return false;
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
            }
        }
        return true;
    }

    private static boolean looselyEquals(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        if (actual instanceof Number && expected instanceof Number) {
            return compare(actual, expected) == 0;
        }
        return Objects.equals(String.valueOf(actual), String.valueOf(expected));
    }

    private static int compare(Object actual, Object expected) {
        if (actual == null && expected == null) return 0;
        if (actual == null) return -1;
        if (expected == null) return 1;

        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return Double.compare(actualNumber.doubleValue(), expectedNumber.doubleValue());
        }
        if (actual instanceof Comparable<?> && expected.getClass().isAssignableFrom(actual.getClass())) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) actual;
            return comparable.compareTo(expected);
        }
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }
}