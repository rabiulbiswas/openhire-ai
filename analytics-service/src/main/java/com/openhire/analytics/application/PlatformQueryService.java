package com.openhire.analytics.application;

import com.openhire.analytics.application.PlatformViews.ConsumerLag;
import com.openhire.analytics.application.PlatformViews.PlatformService;
import com.openhire.analytics.application.PlatformViews.Slo;
import com.openhire.analytics.application.port.AnalyticsPorts.ConsumerOffsets;
import com.openhire.analytics.application.port.AnalyticsPorts.ConsumerOffsets.PartitionOffset;
import com.openhire.analytics.application.port.AnalyticsPorts.HealthStore;
import com.openhire.analytics.application.port.AnalyticsPorts.LatencyFacts;
import com.openhire.analytics.domain.Facts.LatencyFact;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.ServiceSample;
import com.openhire.analytics.domain.SloMath;
import com.openhire.analytics.domain.SloMath.Evaluation;
import com.openhire.analytics.domain.Stats;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Service health, the five SLOs and consumer lag.
 *
 * <p>SLO formulas, all over a rolling window ({@code availabilityWindow}, 24 hours):
 * <ul>
 *   <li>Public API availability: polls of the public API service's health that were UP, over all polls.
 *   <li>Application API latency (approximate): total request time over request count between polls of
 *       {@code http.server.requests} (the mean), and the largest reported maximum. The service reports a decaying
 *       maximum and the counters come from polls, so the figure is an estimate, not a histogram.
 *   <li>Resume processing within 60 s: applications whose {@code ResumeProcessed} event came within the limit of
 *       their {@code ApplicationSubmitted}, over applications that finished or are already overdue.
 *   <li>AI assessment within 2 min: applications whose COMPLETE {@code assessment.completed.v1} came within the limit
 *       of {@code ApplicationScreened}, over those finished or overdue.
 *   <li>Notification hand-off within 60 s: messages whose first delivery update came within the limit of
 *       {@code communication.message.requested.v1}, over those finished or overdue.
 * </ul>
 * Latencies between events are as precise as the event timestamps. Error budget: see {@link SloMath}.
 */
public class PlatformQueryService {
    private static final String WINDOW = "24h";

    private final PlatformSettings settings;
    private final HealthStore health;
    private final LatencyFacts latencies;
    private final ConsumerOffsets offsets;
    private final Clock clock;

    public PlatformQueryService(
            PlatformSettings settings,
            HealthStore health,
            LatencyFacts latencies,
            ConsumerOffsets offsets,
            Clock clock) {
        this.settings = settings;
        this.health = health;
        this.latencies = latencies;
        this.offsets = offsets;
        this.clock = clock;
    }

    /**
     * DOWN when the last poll failed or the service reported DOWN; DEGRADED when it is reachable but not UP, slow
     * (over two seconds) or more than 5 percent of its recent requests (at least 20) failed on the server.
     */
    public List<PlatformService> services() {
        var since = clock.instant().minus(settings.availabilityWindow());
        var result = new ArrayList<PlatformService>();
        for (var name : settings.targets().keySet()) {
            var samples = health.since(name, since);
            if (samples.isEmpty()) {
                result.add(new PlatformService(name, "DOWN", 0, null, 0, null, null));
                continue;
            }
            var last = samples.getLast();
            long up = samples.stream().filter(ServiceSample::up).count();
            boolean metrics = samples.stream().anyMatch(ServiceSample::hasMetrics);
            long requests = samples.stream().mapToLong(ServiceSample::requests).sum();
            long errors = samples.stream().mapToLong(ServiceSample::errors).sum();
            result.add(new PlatformService(
                    name,
                    status(last, samples),
                    Stats.round(100.0 * up / samples.size(), 2),
                    last.at().toString(),
                    last.responseMs(),
                    metrics ? requests : null,
                    metrics ? errors : null));
        }
        return result;
    }

    private String status(ServiceSample last, List<ServiceSample> samples) {
        if (!last.up() && ("DOWN".equals(last.healthStatus()) || "UNREACHABLE".equals(last.healthStatus())))
            return "DOWN";
        if (!last.up() || last.responseMs() > 2000) return "DEGRADED";
        var recent = samples.subList(Math.max(0, samples.size() - 20), samples.size());
        long requests = recent.stream().mapToLong(ServiceSample::requests).sum();
        long errors = recent.stream().mapToLong(ServiceSample::errors).sum();
        return requests >= 20 && errors * 20 > requests ? "DEGRADED" : "UP";
    }

    /** A null tenant (super admin) evaluates event timings across all tenants. */
    public List<Slo> slo(String tenant) {
        var now = clock.instant();
        var since = now.minus(settings.availabilityWindow());
        var result = new ArrayList<Slo>();
        result.add(availability(since));
        result.add(latency(since));
        result.add(events(
                tenant,
                LatencyKind.RESUME_PROCESSING,
                "resume-processing-60s",
                "Resume processing within 60 s",
                settings.resumeTargetPct(),
                settings.resumeLimit(),
                "ApplicationSubmitted to ResumeProcessed",
                since));
        result.add(events(
                tenant,
                LatencyKind.AI_ASSESSMENT,
                "ai-assessment-2m",
                "AI assessment within 2 min",
                settings.aiTargetPct(),
                settings.aiLimit(),
                "ApplicationScreened to the COMPLETE assessment.completed.v1",
                since));
        result.add(events(
                tenant,
                LatencyKind.NOTIFICATION_HANDOFF,
                "notification-handoff-60s",
                "Notification hand-off within 60 s",
                settings.notificationTargetPct(),
                settings.notificationLimit(),
                "communication.message.requested.v1 to the first delivery update",
                since));
        return result;
    }

    private Slo availability(java.time.Instant since) {
        var service = settings.publicApiService();
        var samples = health.since(service, since);
        var label = "Public API availability";
        if (samples.isEmpty())
            return noData("public-api-availability", label, settings.availabilityTargetPct(), "%", service, false);
        long up = samples.stream().filter(ServiceSample::up).count();
        double current = Stats.round(100.0 * up / samples.size(), 2);
        var evaluation = SloMath.atLeast(current, settings.availabilityTargetPct());
        return slo(
                "public-api-availability",
                label,
                settings.availabilityTargetPct(),
                current,
                "%",
                evaluation,
                "%d of %d health polls of %s were UP in the last %s".formatted(up, samples.size(), service, WINDOW),
                false);
    }

    private Slo latency(java.time.Instant since) {
        var service = settings.publicApiService();
        var samples = health.since(service, since).stream()
                .filter(sample -> sample.requests() > 0)
                .toList();
        var label = "Application API latency";
        if (samples.isEmpty())
            return noData("application-api-latency", label, settings.latencyTargetMs(), "ms", service, true);
        long requests = samples.stream().mapToLong(ServiceSample::requests).sum();
        double meanMs = samples.stream().mapToDouble(ServiceSample::timeSeconds).sum() * 1000 / requests;
        double maxMs =
                samples.stream().mapToDouble(ServiceSample::maxSeconds).max().orElse(0) * 1000;
        double current = Stats.round(meanMs, 1);
        var evaluation = SloMath.atMost(current, settings.latencyTargetMs());
        return slo(
                "application-api-latency",
                label,
                settings.latencyTargetMs(),
                current,
                "ms",
                evaluation,
                "Mean over %d requests to %s from polled http.server.requests (max %.0f ms); approximate, from polls not a histogram"
                        .formatted(requests, service, maxMs),
                true);
    }

    private Slo events(
            String tenant,
            LatencyKind kind,
            String id,
            String label,
            double target,
            Duration limit,
            String basis,
            java.time.Instant since) {
        var now = clock.instant();
        long good = 0;
        long bad = 0;
        for (LatencyFact fact : latencies.startedSince(tenant, kind, since)) {
            var duration = fact.duration();
            if (duration.isPresent()) {
                if (duration.get().compareTo(limit) <= 0) good++;
                else bad++;
            } else if (fact.startAt() != null
                    && Duration.between(fact.startAt(), now).compareTo(limit) > 0) bad++;
        }
        long total = good + bad;
        if (total == 0) return noData(id, label, target, "%", basis, true);
        double current = Stats.pct(good, total);
        return slo(
                id,
                label,
                target,
                current,
                "%",
                SloMath.atLeast(current, target),
                "%d of %d within %d s (%s); timings are from event timestamps"
                        .formatted(good, total, limit.toSeconds(), basis),
                true);
    }

    private static Slo slo(
            String id,
            String label,
            double target,
            double current,
            String unit,
            Evaluation evaluation,
            String basis,
            boolean approximate) {
        return new Slo(
                id,
                label,
                target,
                WINDOW,
                current,
                unit,
                evaluation.status(),
                evaluation.errorBudgetRemainingPct(),
                basis,
                approximate);
    }

    /** With nothing observed there is nothing to breach: the budget is untouched and the basis says why. */
    private static Slo noData(String id, String label, double target, String unit, String source, boolean approximate) {
        return new Slo(
                id,
                label,
                target,
                WINDOW,
                0,
                unit,
                SloMath.MET,
                100,
                "No data yet in the window (" + source + ")",
                approximate);
    }

    public List<ConsumerLag> consumers() {
        var lag = new TreeMap<String, TreeMap<String, Long>>();
        for (PartitionOffset offset : offsets.offsets())
            lag.computeIfAbsent(offset.group(), group -> new TreeMap<>())
                    .merge(offset.topic(), Math.max(0, offset.end() - offset.committed()), Long::sum);
        var result = new ArrayList<ConsumerLag>();
        lag.forEach((group, topics) -> topics.forEach((topic, value) ->
                result.add(new ConsumerLag(group, topic, value, value > settings.lagThreshold() ? "LAGGING" : "OK"))));
        result.sort(Comparator.comparing(ConsumerLag::group).thenComparing(ConsumerLag::topic));
        return result;
    }
}
