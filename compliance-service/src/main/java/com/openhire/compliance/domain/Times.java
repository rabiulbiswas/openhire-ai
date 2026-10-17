package com.openhire.compliance.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** All timestamps are stored as fixed-width UTC text with milliseconds, so comparing the text compares the time. */
public final class Times {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {}

    public static String format(Instant instant) {
        return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static String normalise(String text) {
        return text == null || text.isBlank() ? null : format(Instant.parse(text));
    }

    public static Instant parse(String text) {
        return Instant.parse(text);
    }

    public static Instant plusMonths(Instant instant, long months) {
        return instant.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    public static Instant minusMonths(Instant instant, long months) {
        return plusMonths(instant, -months);
    }
}
