package com.openhire.integration.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarEventTest {
    final Instant start = Instant.parse("2026-10-20T10:00:00Z");

    @Test
    void validatesDuration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CalendarEvent("Interview", start, start, "UTC", List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CalendarEvent("Interview", start, start.plusSeconds(86401), "UTC", List.of(), false));
    }

    @Test
    void validatesTimezoneAndAttendees() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CalendarEvent("Interview", start, start.plusSeconds(3600), "Invalid/Zone", List.of(), false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CalendarEvent(
                        "Interview", start, start.plusSeconds(3600), "UTC", List.of("not-an-email"), false));
    }

    @Test
    void normalizesDuplicatesWithoutChangingInstants() {
        var event = new CalendarEvent(
                "Interview",
                start,
                start.plusSeconds(3600),
                "Asia/Kolkata",
                List.of("a@example.com", "a@example.com"),
                true);
        assertEquals(1, event.attendees().size());
        assertEquals(start, event.start());
    }
}
