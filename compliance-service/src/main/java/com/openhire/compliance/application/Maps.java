package com.openhire.compliance.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for reading the JSON-shaped documents the ports exchange. */
final class Maps {
    private Maps() {}

    static String text(Map<String, ?> map, String key) {
        var value = map == null ? null : map.get(key);
        return value == null ? null : value.toString();
    }

    static String text(Map<String, ?> map, String key, String fallback) {
        var value = text(map, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        var out = new ArrayList<Map<String, Object>>();
        if (value instanceof List<?> items)
            for (var item : items) if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    static LinkedHashMap<String, Object> of(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) if (pairs[i + 1] != null) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static LinkedHashMap<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }
}
