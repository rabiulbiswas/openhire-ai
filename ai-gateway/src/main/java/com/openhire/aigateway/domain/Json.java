package com.openhire.aigateway.domain;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON helpers shared by the gateway: model output is handled as plain maps and lists. */
public final class Json {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static Object read(String json) {
        return MAPPER.readValue(json, Object.class);
    }

    public static Map<String, Object> readMap(String json) {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    /** Parses a model reply that should be one JSON object, tolerating code fences and surrounding prose. */
    public static Map<String, Object> parseReply(String reply) {
        if (reply == null || reply.isBlank()) throw new IllegalArgumentException("Empty reply");
        var text = reply.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("Reply is not a JSON object");
        try {
            return readMap(text.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Reply is not valid JSON");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static List<Object> list(Object value) {
        return value instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    public static String string(Object value) {
        return value == null ? "" : value.toString().strip();
    }
}
