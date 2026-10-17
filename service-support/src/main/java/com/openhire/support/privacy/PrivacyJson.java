package com.openhire.support.privacy;

import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

/** Turns stored documents into plain JSON-safe values for a privacy export. */
public final class PrivacyJson {
    private PrivacyJson() {}

    /** Maps, lists and scalars with dates as ISO text; binaries are dropped. Keys in {@code omit} are removed at any depth. */
    public static Object safe(Object value, Set<String> omit) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                if (omit.contains(String.valueOf(key)) || item instanceof Binary || item instanceof byte[]) return;
                result.put(String.valueOf(key), safe(item, omit));
            });
            return result;
        }
        if (value instanceof Collection<?> items)
            return items.stream()
                    .filter(item -> !(item instanceof Binary || item instanceof byte[]))
                    .map(item -> safe(item, omit))
                    .toList();
        if (value instanceof Date date) return date.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof ObjectId id) return id.toHexString();
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, ?> document, String... omit) {
        return (Map<String, Object>) safe(document, Set.of(omit));
    }

    public static List<Map<String, Object>> maps(Collection<? extends Map<String, ?>> documents, String... omit) {
        return documents.stream().map(document -> map(document, omit)).toList();
    }
}
