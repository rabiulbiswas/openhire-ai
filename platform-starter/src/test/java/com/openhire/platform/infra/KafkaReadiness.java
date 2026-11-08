package com.openhire.platform.infra;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.RetriableException;

/** Bounded retries for transient broker readiness failures, not authentication or configuration errors. */
final class KafkaReadiness {
    @FunctionalInterface
    interface Probe {
        void verify(Duration remaining) throws Exception;
    }

    private KafkaReadiness() {}

    static void await(Duration timeout, Duration interval, Probe probe) throws Exception {
        if (timeout.isNegative() || timeout.isZero() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Timeout and poll interval must be positive");
        }
        long started = System.nanoTime();
        long budget = timeout.toNanos();
        Exception lastFailure = null;
        int attempts = 0;
        while (true) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new AssertionError(
                        "Kafka was not ready within " + timeout + " after " + attempts + " attempts", lastFailure);
            }
            attempts++;
            try {
                probe.verify(Duration.ofNanos(remaining));
                return;
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof RetriableException)) throw failure;
                lastFailure = failure;
            } catch (RetriableException | TimeoutException failure) {
                lastFailure = failure;
            }
            remaining = budget - (System.nanoTime() - started);
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(Math.min(interval.toNanos(), remaining));
        }
    }
}
