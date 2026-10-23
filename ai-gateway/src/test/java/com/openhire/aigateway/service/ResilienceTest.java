package com.openhire.aigateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.aigateway.Fixtures.MutableClock;
import com.openhire.aigateway.domain.AiTypes.CircuitState;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResilienceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T05:00:00Z"));

    @Test
    void opensAfterConsecutiveFailuresThenProbesOnceAndCloses() {
        var breaker = new CircuitBreaker(3, Duration.ofSeconds(30), clock);
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofSeconds(31));
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("first probe").isTrue();
        assertThat(breaker.tryAcquire()).as("others wait for the probe").isFalse();

        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeOpensTheCircuitAgain() {
        var breaker = new CircuitBreaker(1, Duration.ofSeconds(10), clock);
        breaker.onFailure();
        clock.advance(Duration.ofSeconds(11));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        var breaker = new CircuitBreaker(2, Duration.ofSeconds(10), clock);
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    void bulkheadLimitsConcurrencyAndReleasesPermits() {
        var bulkhead = new Bulkhead(2, Duration.ofMillis(50));
        var first = bulkhead.acquire();
        var second = bulkhead.acquire();
        assertThat(bulkhead.available()).isZero();
        assertThatThrownBy(bulkhead::acquire).isInstanceOf(AiUnavailable.class).hasMessageContaining("busy");
        first.close();
        first.close(); // closing twice must not hand out extra permits
        assertThat(bulkhead.available()).isEqualTo(1);
        try (var third = bulkhead.acquire()) {
            assertThat(bulkhead.available()).isZero();
        }
        second.close();
        assertThat(bulkhead.available()).isEqualTo(2);
    }
}
