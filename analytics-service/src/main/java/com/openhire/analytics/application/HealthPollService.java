package com.openhire.analytics.application;

import com.openhire.analytics.application.port.AnalyticsPorts.ActuatorClient;
import com.openhire.analytics.application.port.AnalyticsPorts.ActuatorClient.Health;
import com.openhire.analytics.application.port.AnalyticsPorts.ActuatorClient.HttpMetrics;
import com.openhire.analytics.application.port.AnalyticsPorts.HealthStore;
import com.openhire.analytics.domain.ServiceSample;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls every target's {@code /actuator/health} and {@code /actuator/metrics/http.server.requests}. The metrics are
 * cumulative since the service started, so rates come from the difference between consecutive polls; a counter that
 * went down means the service restarted and its whole value is new.
 */
public class HealthPollService {
    private static final Logger LOG = LoggerFactory.getLogger(HealthPollService.class);

    private final PlatformSettings settings;
    private final ActuatorClient actuator;
    private final HealthStore store;
    private final Clock clock;

    public HealthPollService(PlatformSettings settings, ActuatorClient actuator, HealthStore store, Clock clock) {
        this.settings = settings;
        this.actuator = actuator;
        this.store = store;
        this.clock = clock;
    }

    /** Polls all targets concurrently and returns how many were up. */
    public int pollAll() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<CompletableFuture<Boolean>>();
            settings.targets()
                    .forEach((name, url) -> futures.add(CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return poll(name, url).up();
                                } catch (RuntimeException failure) {
                                    LOG.warn("Health poll of {} failed: {}", name, failure.toString());
                                    return false;
                                }
                            },
                            executor)));
            return (int) futures.stream().filter(CompletableFuture::join).count();
        }
    }

    ServiceSample poll(String name, String url) {
        var at = clock.instant();
        Health health = actuator.health(url);
        Optional<HttpMetrics> total = health.reachable() ? actuator.httpMetrics(url, false) : Optional.empty();
        long errorCount = -1;
        if (total.isPresent())
            errorCount = actuator.httpMetrics(url, true).map(HttpMetrics::count).orElse(0L);
        var previous = store.latest(name).filter(ServiceSample::hasMetrics);
        long requests = 0;
        long errors = 0;
        double seconds = 0;
        if (total.isPresent() && previous.isPresent()) {
            var before = previous.get();
            boolean restarted = total.get().count() < before.requestCount();
            requests = restarted ? total.get().count() : total.get().count() - before.requestCount();
            errors = restarted ? errorCount : Math.max(0, errorCount - before.errorCount());
            seconds = restarted
                    ? total.get().totalSeconds()
                    : Math.max(0, total.get().totalSeconds() - before.totalSeconds());
        }
        var sample = new ServiceSample(
                name,
                at,
                health.reachable() && "UP".equals(health.status()),
                health.status(),
                health.responseMs(),
                total.map(HttpMetrics::count).orElse(-1L),
                total.map(HttpMetrics::totalSeconds).orElse(0.0),
                errorCount,
                requests,
                errors,
                seconds,
                total.map(HttpMetrics::maxSeconds).orElse(0.0));
        store.add(sample);
        return sample;
    }
}
