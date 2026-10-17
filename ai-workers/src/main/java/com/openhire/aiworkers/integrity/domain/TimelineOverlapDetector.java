package com.openhire.aiworkers.integrity.domain;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Overlapping full-time roles in the structured resume. Short overlaps (a notice period) stay below threshold. */
public final class TimelineOverlapDetector implements Detector {
    private final Clock clock;

    public TimelineOverlapDetector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "TIMELINE_OVERLAP";
    }

    @Override
    public double threshold() {
        return 0.5;
    }

    @Override
    public Optional<Finding> detect(DetectionContext context) {
        var roles = context.roles().get().stream().filter(Role::fullTime).toList();
        long worst = 0;
        for (int i = 0; i < roles.size(); i++)
            for (int j = i + 1; j < roles.size(); j++)
                worst = Math.max(worst, overlapMonths(roles.get(i), roles.get(j)));
        if (worst <= 0) return Optional.empty();
        double confidence = Math.min(0.95, 0.3 + worst * 0.07);
        var severity = worst >= 12 ? "HIGH" : worst >= 6 ? "MEDIUM" : "LOW";
        return Optional.of(new Finding(severity, confidence, "Two full-time roles overlap for " + worst + " months."));
    }

    long overlapMonths(Role a, Role b) {
        var startA = month(a.start());
        var startB = month(b.start());
        if (startA == null || startB == null) return 0;
        var now = YearMonth.now(clock);
        var endA = a.end() == null || a.end().isBlank() ? now : month(a.end());
        var endB = b.end() == null || b.end().isBlank() ? now : month(b.end());
        if (endA == null || endB == null) return 0;
        var from = startA.isAfter(startB) ? startA : startB;
        var to = endA.isBefore(endB) ? endA : endB;
        return Math.max(0, ChronoUnit.MONTHS.between(from, to));
    }

    private static YearMonth month(String value) {
        try {
            return value == null ? null : YearMonth.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
