package com.openhire.integration.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

public record CalendarEvent(
        String title, Instant start, Instant end, String timezone, List<String> attendees, boolean onlineMeeting) {
    public CalendarEvent {
        if (title == null || title.isBlank() || title.length() > 200)
            throw new IllegalArgumentException("Event title required (maximum 200 characters)");
        if (start == null
                || end == null
                || !end.isAfter(start)
                || Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("Event duration must be positive and at most 24 hours");
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Valid IANA timezone required");
        }
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
        if (attendees.size() > 30
                || attendees.stream()
                        .anyMatch(e -> e == null
                                || e.length() > 254
                                || !e.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")))
            throw new IllegalArgumentException("At most 30 valid attendee email addresses are allowed");
        attendees = attendees.stream().distinct().sorted().toList();
    }
}
