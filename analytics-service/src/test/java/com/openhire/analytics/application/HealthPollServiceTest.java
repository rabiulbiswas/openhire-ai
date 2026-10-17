package com.openhire.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.analytics.application.Memory.Health;
import com.openhire.analytics.application.Memory.MutableClock;
import com.openhire.analytics.application.port.AnalyticsPorts.ActuatorClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HealthPollServiceTest {
    private static final class FakeActuator implements ActuatorClient {
        final Map<String, Health> health = new HashMap<>();
        final Map<String, HttpMetrics> total = new HashMap<>();
        final Map<String, HttpMetrics> errors = new HashMap<>();
        final List<String> metricCalls = new ArrayList<>();

        @Override
        public Health health(String baseUrl) {
            return health.getOrDefault(baseUrl, new Health(false, "UNREACHABLE", 3000));
        }

        @Override
        public Optional<HttpMetrics> httpMetrics(String baseUrl, boolean serverErrorsOnly) {
            metricCalls.add(baseUrl);
            return Optional.ofNullable((serverErrorsOnly ? errors : total).get(baseUrl));
        }
    }

    private final FakeActuator actuator = new FakeActuator();
    private final Memory.Health store = new Memory.Health();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
    private final HealthPollService poller = new HealthPollService(
            PlatformSettings.defaults(new LinkedHashMap<>(Map.of("svc", "http://svc", "other", "http://other"))),
            actuator,
            store,
            clock);

    @Test
    void ratesComeFromTheDifferenceBetweenPolls() {
        actuator.health.put("http://svc", new ActuatorClient.Health(true, "UP", 12));
        actuator.total.put("http://svc", new ActuatorClient.HttpMetrics(100, 10.0, 0.5));
        actuator.errors.put("http://svc", new ActuatorClient.HttpMetrics(2, 1.0, 0.1));
        var first = poller.poll("svc", "http://svc");
        assertThat(first.up()).isTrue();
        assertThat(first.requests()).isZero();

        clock.advance(Duration.ofSeconds(15));
        actuator.total.put("http://svc", new ActuatorClient.HttpMetrics(160, 12.0, 0.9));
        actuator.errors.put("http://svc", new ActuatorClient.HttpMetrics(5, 2.0, 0.2));
        var second = poller.poll("svc", "http://svc");

        assertThat(second.requests()).isEqualTo(60);
        assertThat(second.errors()).isEqualTo(3);
        assertThat(second.timeSeconds()).isEqualTo(2.0);
        assertThat(second.maxSeconds()).isEqualTo(0.9);
        assertThat(second.responseMs()).isEqualTo(12);
        assertThat(store.samples).hasSize(2);
    }

    @Test
    void aCounterThatWentDownMeansTheServiceRestarted() {
        actuator.health.put("http://svc", new ActuatorClient.Health(true, "UP", 5));
        actuator.total.put("http://svc", new ActuatorClient.HttpMetrics(500, 50.0, 1));
        actuator.errors.put("http://svc", new ActuatorClient.HttpMetrics(9, 5.0, 1));
        poller.poll("svc", "http://svc");
        clock.advance(Duration.ofSeconds(15));
        actuator.total.put("http://svc", new ActuatorClient.HttpMetrics(20, 2.0, 0.3));
        actuator.errors.put("http://svc", new ActuatorClient.HttpMetrics(1, 0.5, 0.3));

        var sample = poller.poll("svc", "http://svc");

        assertThat(sample.requests()).isEqualTo(20);
        assertThat(sample.errors()).isEqualTo(1);
        assertThat(sample.timeSeconds()).isEqualTo(2.0);
    }

    @Test
    void anUnreachableServiceIsDownAndItsMetricsAreNotAsked() {
        var sample = poller.poll("svc", "http://svc");

        assertThat(sample.up()).isFalse();
        assertThat(sample.healthStatus()).isEqualTo("UNREACHABLE");
        assertThat(sample.hasMetrics()).isFalse();
        assertThat(actuator.metricCalls).isEmpty();
    }

    @Test
    void aServiceReportingDownIsNotUpEvenThoughItAnswered() {
        actuator.health.put("http://svc", new ActuatorClient.Health(true, "DOWN", 8));

        var sample = poller.poll("svc", "http://svc");

        assertThat(sample.up()).isFalse();
        assertThat(sample.healthStatus()).isEqualTo("DOWN");
    }

    @Test
    void aServiceWithoutMetricsIsStillTracked() {
        actuator.health.put("http://svc", new ActuatorClient.Health(true, "UP", 8));

        var sample = poller.poll("svc", "http://svc");

        assertThat(sample.up()).isTrue();
        assertThat(sample.hasMetrics()).isFalse();
        assertThat(sample.requests()).isZero();
    }

    @Test
    void pollingAllTargetsStoresASamplePerServiceAndCountsTheUpOnes() {
        actuator.health.put("http://svc", new ActuatorClient.Health(true, "UP", 8));

        assertThat(poller.pollAll()).isEqualTo(1);

        assertThat(store.samples).extracting("service").containsExactlyInAnyOrder("svc", "other");
    }
}
