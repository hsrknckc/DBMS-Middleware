package middleware.storage;

import java.util.Map;
import java.util.regex.Pattern;
import org.bson.Document;

public class MongoQueryBuilder {

    /**
     * İstemciden gelen güvenli Map filtresini MongoDB Document sorgusuna dönüştürür.
     */
    @SuppressWarnings("unchecked")
    public static Document buildQuery(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return new Document();
        }

        Document query = new Document();

        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            String field = entry.getKey();
            Object val = entry.getValue();

            // Eğer değer bir Map ise (yani {"age": {">": 20}} gibi operatörlü geldiyse)
            if (val instanceof Map<?, ?> opMap) {
                Document fieldOps = new Document();

                for (Map.Entry<?, ?> opEntry : opMap.entrySet()) {
                    String op = String.valueOf(opEntry.getKey()).toLowerCase().trim();
                    Object targetVal = opEntry.getValue();

                    switch (op) {
                        case ">", "gt" -> fieldOps.append("$gt", targetVal);
                        case ">=", "gte" -> fieldOps.append("$gte", targetVal);
                        case "<", "lt" -> fieldOps.append("$lt", targetVal);
                        case "<=", "lte" -> fieldOps.append("$lte", targetVal);
                        case "!=", "ne" -> fieldOps.append("$ne", targetVal);
                        case "like", "contains" -> {
                            // Güvenli regex: Metin içinde geçen kısmı case-insensitive arar
                            String safeRegex = Pattern.quote(String.valueOf(targetVal));
                            fieldOps.append("$regex", safeRegex).append("$options", "i");
                        }
                        default -> throw new IllegalArgumentException("Unsupported filter operator: " + op);
                    }
                }
                query.append(field, fieldOps);
            } else {
                // Düz eşitlik sorgusu: {"status": "ACTIVE"}
                query.append(field, val);
            }
        }

        return query;
    }
}