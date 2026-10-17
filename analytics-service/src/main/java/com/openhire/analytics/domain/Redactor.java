package com.openhire.analytics.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Masks personal data in dead-letter payloads. A value is masked when its key looks like a name, contact detail,
 * free text or document content; identifiers are always kept, and anything shaped like an email address is masked
 * wherever it appears.
 */
public final class Redactor {
    public static final String MASK = "***";
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(name|email|phone|address|text|resume|note|message|body|subject|content|comment|reason).*");
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)(id|.*[a-z]Id|.*_id|.*Ids)");
    private static final Pattern EMAIL = Pattern.compile("[^\\s\"@]+@[^\\s\"@]+\\.[^\\s\"@]+");

    private Redactor() {}

    public static Object redact(Object value) {
        return walk(null, value);
    }

    public static String text(String value) {
        return value == null ? "" : EMAIL.matcher(value).replaceAll(MASK);
    }

    private static Object walk(String key, Object value) {
        if (key != null
                && !IDENTIFIER.matcher(key).matches()
                && SENSITIVE.matcher(key).matches()) return value == null ? null : MASK;
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> out.put(String.valueOf(k), walk(String.valueOf(k), v)));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>();
            list.forEach(item -> out.add(walk(key, item)));
            return out;
        }
        return value instanceof String string ? text(string) : value;
    }
}
