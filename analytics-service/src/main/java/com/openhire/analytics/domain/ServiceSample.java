package com.openhire.analytics.domain;

import java.time.Instant;

/**
 * One poll of a service. The first four counters are what the service reported (cumulative since it started, -1 when
 * not available); the last three are the change since the previous poll, which is what rates are computed from.
 */
public record ServiceSample(
        String service,
        Instant at,
        boolean up,
        String healthStatus,
        long responseMs,
        long requestCount,
        double totalSeconds,
        long errorCount,
        long requests,
        long errors,
        double timeSeconds,
        double maxSeconds) {
    public boolean hasMetrics() {
        return requestCount >= 0;
    }
}
