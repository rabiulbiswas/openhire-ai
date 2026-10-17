package com.openhire.analytics.domain;

import java.time.Duration;
import java.time.Instant;

/** A reporting window and the equally long window before it, used for period-over-period deltas. */
public record Range(Instant from, Instant to, Instant previousFrom) {
    public static Range of(String key, Instant now) {
        long days =
                switch (key == null || key.isBlank() ? "30d" : key.trim().toLowerCase(java.util.Locale.ROOT)) {
                    case "30d" -> 30;
                    case "90d" -> 90;
                    case "1y" -> 365;
                    default -> throw new IllegalArgumentException("range must be 30d, 90d or 1y");
                };
        var length = Duration.ofDays(days);
        return new Range(now.minus(length), now, now.minus(length.multipliedBy(2)));
    }

    /** Windows are half open, (from, to], so consecutive periods partition time without overlap. */
    public boolean contains(Instant at) {
        return at != null && at.isAfter(from) && !at.isAfter(to);
    }

    public Range previous() {
        return new Range(previousFrom, from, previousFrom.minus(Duration.between(previousFrom, from)));
    }
}
