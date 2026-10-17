package com.openhire.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.interview.domain.SlotFinder.Interval;
import com.openhire.interview.domain.SlotFinder.Policy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotFinderTest {
    private static final Duration HOUR = Duration.ofHours(1);

    private static Policy many() {
        var standard = Policy.standard();
        return new Policy(
                standard.dayStart(),
                standard.dayEnd(),
                standard.workdays(),
                standard.step(),
                standard.buffer(),
                standard.minimumNotice(),
                standard.horizonDays(),
                200);
    }

    @Test
    void offersOnlyWorkdayTimesInsideWorkingHoursInTheRoundTimeZone() {
        var zone = ZoneId.of("Asia/Kolkata");
        var now = ZonedDateTime.of(2026, 10, 9, 8, 0, 0, 0, zone).toInstant(); // Friday morning
        var slots = SlotFinder.propose(now, zone, HOUR, List.of(), many());

        assertThat(slots).isNotEmpty().allSatisfy(start -> {
            var local = start.atZone(zone);
            assertThat(local.getDayOfWeek().getValue()).isLessThanOrEqualTo(5);
            assertThat(local.toLocalTime()).isBetween(LocalTime.of(9, 0), LocalTime.of(17, 0));
            assertThat(start).isAfterOrEqualTo(now.plus(Duration.ofHours(2)));
        });
    }

    @Test
    void keepsWallClockHoursAcrossTheLondonSpringForward() {
        var zone = ZoneId.of("Europe/London");
        var now = ZonedDateTime.of(2026, 3, 26, 7, 0, 0, 0, zone).toInstant(); // DST starts 2026-03-29
        var slots = SlotFinder.propose(now, zone, HOUR, List.of(), many());

        var friday = firstOn(slots, zone, LocalDate.of(2026, 3, 27));
        var monday = firstOn(slots, zone, LocalDate.of(2026, 3, 30));
        assertThat(friday.atZone(zone).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.atZone(zone).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        // 09:00 GMT before the change, 09:00 BST (08:00 UTC) after it.
        assertThat(friday.atZone(ZoneId.of("UTC")).getHour()).isEqualTo(9);
        assertThat(monday.atZone(ZoneId.of("UTC")).getHour()).isEqualTo(8);
    }

    @Test
    void keepsWallClockHoursAcrossTheNewYorkFallBack() {
        var zone = ZoneId.of("America/New_York");
        var now = ZonedDateTime.of(2026, 10, 29, 7, 0, 0, 0, zone).toInstant(); // DST ends 2026-11-01
        var slots = SlotFinder.propose(now, zone, HOUR, List.of(), many());

        var monday = firstOn(slots, zone, LocalDate.of(2026, 11, 2));
        assertThat(monday.atZone(zone).toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.atZone(ZoneId.of("UTC")).getHour()).isEqualTo(14);
    }

    @Test
    void neverOverlapsBusyTimePlusTheBuffer() {
        var zone = ZoneId.of("Asia/Kolkata");
        var now = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, zone).toInstant(); // Monday
        var busyStart = ZonedDateTime.of(2026, 10, 12, 13, 0, 0, 0, zone).toInstant();
        var busy = List.of(new Interval(busyStart, busyStart.plus(HOUR)));

        var slots = SlotFinder.propose(now, zone, HOUR, busy, many());

        assertThat(slots)
                .noneMatch(start -> start.isBefore(busyStart.plus(HOUR).plus(Duration.ofMinutes(15)))
                        && start.plus(HOUR).plus(Duration.ofMinutes(15)).isAfter(busyStart));
        assertThat(slots)
                .doesNotContain(
                        ZonedDateTime.of(2026, 10, 12, 12, 0, 0, 0, zone).toInstant());
        assertThat(slots)
                .contains(ZonedDateTime.of(2026, 10, 12, 14, 30, 0, 0, zone).toInstant());
    }

    @Test
    void spreadsTheDefaultOfferAcrossDifferentDays() {
        var zone = ZoneId.of("Asia/Kolkata");
        var now = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, zone).toInstant();
        var slots = SlotFinder.propose(now, zone, HOUR, List.of(), Policy.standard());

        assertThat(slots).hasSize(5);
        assertThat(slots.stream().map(start -> start.atZone(zone).toLocalDate()).distinct())
                .hasSize(5);
    }

    @Test
    void blocksCoverTheWholeIntervalOnTheQuarterHourGrid() {
        var start = Instant.parse("2026-10-12T05:40:00Z");
        var blocks = SlotFinder.blocks(start, start.plus(HOUR));

        assertThat(blocks.getFirst()).isEqualTo(Instant.parse("2026-10-12T05:30:00Z"));
        assertThat(blocks.getLast()).isEqualTo(Instant.parse("2026-10-12T06:30:00Z"));
        assertThat(blocks).hasSize(5);
    }

    private static Instant firstOn(List<Instant> slots, ZoneId zone, LocalDate date) {
        return slots.stream()
                .filter(start -> start.atZone(zone).toLocalDate().equals(date))
                .findFirst()
                .orElseThrow();
    }
}
