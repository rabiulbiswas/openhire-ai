package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTypes.CircuitState;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The circuit breaker and bulkhead of every provider, created on first use. */
public class ProviderGuards {
    public record Guard(CircuitBreaker breaker, Bulkhead bulkhead) {}

    private final Map<String, Guard> guards = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int failures;
    private final Duration openFor;
    private final int concurrency;
    private final Duration queueWait;

    public ProviderGuards(Clock clock, int failures, Duration openFor, int concurrency, Duration queueWait) {
        this.clock = clock;
        this.failures = failures;
        this.openFor = openFor;
        this.concurrency = concurrency;
        this.queueWait = queueWait;
    }

    public Guard of(String providerId) {
        return guards.computeIfAbsent(
                providerId,
                id -> new Guard(new CircuitBreaker(failures, openFor, clock), new Bulkhead(concurrency, queueWait)));
    }

    public CircuitState state(String providerId) {
        return guards.containsKey(providerId) ? guards.get(providerId).breaker().state() : CircuitState.CLOSED;
    }
}
