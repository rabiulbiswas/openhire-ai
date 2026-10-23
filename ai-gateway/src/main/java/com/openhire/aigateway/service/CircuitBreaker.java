package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTypes.CircuitState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Opens after {@code threshold} consecutive failures and stays open for {@code openFor}; then lets exactly one probe
 * through (half-open). A successful probe closes it, a failed probe opens it again.
 */
public class CircuitBreaker {
    private final int threshold;
    private final Duration openFor;
    private final Clock clock;
    private int failures;
    private Instant openedAt;
    private boolean probing;

    public CircuitBreaker(int threshold, Duration openFor, Clock clock) {
        this.threshold = Math.max(1, threshold);
        this.openFor = openFor;
        this.clock = clock;
    }

    /** Whether a call may go ahead now. In half-open state only one caller gets true until the probe reports. */
    public synchronized boolean tryAcquire() {
        if (openedAt == null) return true;
        var now = clock.instant();
        if (now.isBefore(openedAt.plus(openFor))) return false;
        if (probing && now.isBefore(openedAt.plus(openFor).plus(openFor))) return false;
        probing = true;
        return true;
    }

    public synchronized void onSuccess() {
        failures = 0;
        openedAt = null;
        probing = false;
    }

    public synchronized void onFailure() {
        failures++;
        if (probing || failures >= threshold) {
            openedAt = clock.instant();
            probing = false;
        }
    }

    public synchronized CircuitState state() {
        if (openedAt == null) return CircuitState.CLOSED;
        return clock.instant().isBefore(openedAt.plus(openFor)) ? CircuitState.OPEN : CircuitState.HALF_OPEN;
    }
}
