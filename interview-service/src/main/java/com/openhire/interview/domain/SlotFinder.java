package com.openhire.interview.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Proposes interview start times inside the panel's working hours, avoiding busy intervals plus a buffer.
 * Works in the round's time zone day by day, so daylight-saving transitions shift the instants correctly.
 */
public final class SlotFinder {
    public static final Duration BLOCK = Duration.ofMinutes(15);

    public record Interval(Instant start, Instant end) {
        public boolean overlaps(Instant otherStart, Instant otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    public record Policy(
            LocalTime dayStart,
            LocalTime dayEnd,
            Set<DayOfWeek> workdays,
            Duration step,
            Duration buffer,
            Duration minimumNotice,
            int horizonDays,
            int maxSlots) {
        public static Policy standard() {
            return new Policy(
                    LocalTime.of(9, 0),
                    LocalTime.of(18, 0),
                    Set.of(
                            DayOfWeek.MONDAY,
                            DayOfWeek.TUESDAY,
                            DayOfWeek.WEDNESDAY,
                            DayOfWeek.THURSDAY,
                            DayOfWeek.FRIDAY),
                    Duration.ofMinutes(30),
                    Duration.ofMinutes(15),
                    Duration.ofHours(2),
                    14,
                    5);
        }
    }

    private SlotFinder() {}

    public static List<Instant> propose(Instant now, ZoneId zone, Duration length, List<Interval> busy, Policy policy) {
        var earliest = now.plus(policy.minimumNotice());
        var candidates = new ArrayList<Instant>();
        var firstDay = LocalDate.ofInstant(now, zone);
        for (int day = 0; day <= policy.horizonDays(); day++) {
            var date = firstDay.plusDays(day);
            if (!policy.workdays().contains(date.getDayOfWeek())) continue;
            var dayEnd = ZonedDateTime.of(date, policy.dayEnd(), zone).toInstant();
            long window = Duration.between(policy.dayStart(), policy.dayEnd()).toMinutes();
            for (long offset = 0;
                    offset + length.toMinutes() <= window;
                    offset += policy.step().toMinutes()) {
                var local = policy.dayStart().plusMinutes(offset);
                var start = ZonedDateTime.of(date, local, zone);
                // Skip local times that fall into a spring-forward gap: the zone would silently shift them.
                if (!start.toLocalTime().equals(local)) continue;
                var startInstant = start.toInstant();
                var endInstant = startInstant.plus(length);
                if (startInstant.isBefore(earliest) || endInstant.isAfter(dayEnd)) continue;
                var paddedStart = startInstant.minus(policy.buffer());
                var paddedEnd = endInstant.plus(policy.buffer());
                if (busy.stream().anyMatch(value -> value.overlaps(paddedStart, paddedEnd))) continue;
                candidates.add(startInstant);
            }
        }
        return spread(candidates, zone, policy.maxSlots());
    }

    /** Prefer one option per day before offering several on the same day. */
    private static List<Instant> spread(List<Instant> candidates, ZoneId zone, int max) {
        var chosen = new LinkedHashSet<Instant>();
        var days = new LinkedHashSet<LocalDate>();
        for (var value : candidates) {
            if (chosen.size() == max) break;
            if (days.add(LocalDate.ofInstant(value, zone))) chosen.add(value);
        }
        for (var value : candidates) {
            if (chosen.size() == max) break;
            chosen.add(value);
        }
        return chosen.stream().sorted().toList();
    }

    /** 15-minute blocks covering [start, end); bookings reserve every block so any overlap collides. */
    public static List<Instant> blocks(Instant start, Instant end) {
        var result = new ArrayList<Instant>();
        var cursor =
                Instant.ofEpochSecond(Math.floorDiv(start.getEpochSecond(), BLOCK.getSeconds()) * BLOCK.getSeconds());
        while (cursor.isBefore(end)) {
            result.add(cursor);
            cursor = cursor.plus(BLOCK);
        }
        return result;
    }
}
