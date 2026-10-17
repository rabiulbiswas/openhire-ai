package com.openhire.analytics.application;

import java.time.Duration;
import java.util.Map;

/** Tunables of platform operations. Targets keep their configured order. */
public record PlatformSettings(
        Map<String, String> targets,
        String publicApiService,
        Duration availabilityWindow,
        long lagThreshold,
        double availabilityTargetPct,
        double latencyTargetMs,
        double resumeTargetPct,
        Duration resumeLimit,
        double aiTargetPct,
        Duration aiLimit,
        double notificationTargetPct,
        Duration notificationLimit,
        int replayPerMinute,
        int peekPerPartition) {
    public static PlatformSettings defaults(Map<String, String> targets) {
        return new PlatformSettings(
                targets,
                "application-service",
                Duration.ofHours(24),
                100,
                99.5,
                500,
                95,
                Duration.ofSeconds(60),
                90,
                Duration.ofMinutes(2),
                99,
                Duration.ofSeconds(60),
                20,
                200);
    }
}
