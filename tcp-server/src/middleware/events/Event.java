package middleware.events;
import java.util.Map;

public record Event(String type, String collection, Map<String, Object> data) {}