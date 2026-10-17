package com.openhire.analytics.adapter.out.mongo;

import java.time.Instant;
import java.util.Date;
import org.bson.Document;

final class Docs {
    static final int MAX_ROWS = 200_000;

    private Docs() {}

    static Date date(Instant value) {
        return value == null ? null : Date.from(value);
    }

    static Instant instant(Document document, String key) {
        return document.get(key) instanceof Date date ? date.toInstant() : null;
    }

    static String text(Document document, String key) {
        var value = document.get(key);
        return value == null ? null : value.toString();
    }

    static long number(Document document, String key) {
        return document.get(key) instanceof Number number ? number.longValue() : 0;
    }

    static String key(String... parts) {
        return String.join("|", parts);
    }
}
