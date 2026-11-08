package com.openhire.platform.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.common.errors.AuthenticationException;
import org.junit.jupiter.api.Test;

class KafkaReadinessTest {
    @Test
    void retriesTransientClientExitAndThenSucceeds() throws Exception {
        var attempts = new AtomicInteger();
        KafkaReadiness.await(Duration.ofSeconds(2), Duration.ofMillis(1), remaining -> {
            assertThat(remaining).isPositive();
            if (attempts.incrementAndGet() == 1) {
                throw new ExecutionException(
                        new org.apache.kafka.common.errors.TimeoutException("AdminClient thread exited"));
            }
        });
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void retriesFutureTimeouts() throws Exception {
        var attempts = new AtomicInteger();
        KafkaReadiness.await(Duration.ofSeconds(2), Duration.ofMillis(1), remaining -> {
            if (attempts.incrementAndGet() == 1) throw new TimeoutException("Metadata not ready");
        });
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void persistentFailureIsNotHiddenAndRetainsItsCause() {
        var failure = new ExecutionException(new org.apache.kafka.common.errors.TimeoutException("Broker unavailable"));
        assertThatThrownBy(() -> KafkaReadiness.await(Duration.ofMillis(20), Duration.ofMillis(1), remaining -> {
                    throw failure;
                }))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Kafka was not ready within")
                .hasCause(failure);
    }

    @Test
    void authenticationFailureDoesNotRetry() {
        var attempts = new AtomicInteger();
        var failure = new ExecutionException(new AuthenticationException("Invalid credentials"));
        assertThatThrownBy(() -> KafkaReadiness.await(Duration.ofSeconds(2), Duration.ofMillis(1), remaining -> {
                    attempts.incrementAndGet();
                    throw failure;
                }))
                .isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void configurationFailureDoesNotRetry() {
        var attempts = new AtomicInteger();
        var failure = new IllegalArgumentException("Invalid bootstrap server");
        assertThatThrownBy(() -> KafkaReadiness.await(Duration.ofSeconds(2), Duration.ofMillis(1), remaining -> {
                    attempts.incrementAndGet();
                    throw failure;
                }))
                .isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void interruptionIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> KafkaReadiness.await(Duration.ofSeconds(2), Duration.ofMillis(1), remaining -> {
                        throw new TimeoutException("Not ready");
                    }))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsUnboundedOrBusyLoopSettings() {
        assertThatThrownBy(() -> KafkaReadiness.await(Duration.ZERO, Duration.ofMillis(1), remaining -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaReadiness.await(Duration.ofSeconds(1), Duration.ZERO, remaining -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
