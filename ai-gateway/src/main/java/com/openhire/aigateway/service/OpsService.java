package com.openhire.aigateway.service;

import com.openhire.aigateway.service.port.AiPorts.ProviderCatalog;
import com.openhire.aigateway.service.port.AiPorts.RequestLog;
import com.openhire.aigateway.service.port.AiPorts.RequestStat;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Operational summary of live AI traffic (evaluation calls are excluded). */
public class OpsService {
    public record TaskLine(
            String task,
            String model,
            int requests,
            long p95LatencyMs,
            double errorPct,
            double cacheHitPct,
            double costInr) {}

    public record Circuit(String provider, String state) {}

    public record Summary(
            String range,
            int assessmentsCompleted,
            double providerErrorRatePct,
            double fallbackToRulesPct,
            double guardrailRejectionsPct,
            List<TaskLine> byTask,
            List<Circuit> circuits) {}

    private static final Set<String> SUCCESS = Set.of("OK", "CACHED", "REPAIRED");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,3})([hd])");

    private final RequestLog log;
    private final ProviderCatalog providers;
    private final ProviderGuards guards;
    private final Clock clock;

    public OpsService(RequestLog log, ProviderCatalog providers, ProviderGuards guards, Clock clock) {
        this.log = log;
        this.providers = providers;
        this.guards = guards;
        this.clock = clock;
    }

    public Summary summary(String tenantId, String range) {
        var text = range == null || range.isBlank() ? "24h" : range.strip().toLowerCase();
        var m = RANGE.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("range must look like 24h or 7d");
        var span = "h".equals(m.group(2))
                ? Duration.ofHours(Long.parseLong(m.group(1)))
                : Duration.ofDays(Long.parseLong(m.group(1)));
        var stats = log.since(tenantId, clock.instant().minus(span));
        double total = Math.max(1, stats.size());
        var byTask = stats.stream()
                .collect(
                        Collectors.groupingBy(s -> s.task() + "|" + s.model(), LinkedHashMap::new, Collectors.toList()))
                .values()
                .stream()
                .map(OpsService::line)
                .sorted(Comparator.comparing(TaskLine::task))
                .toList();
        return new Summary(
                text,
                (int) stats.stream()
                        .filter(s -> s.task().equals("match.judge") && SUCCESS.contains(s.outcome()))
                        .count(),
                round(count(stats, Set.of("PROVIDER_ERROR")) / total * 100),
                round(count(stats, Set.of("PROVIDER_ERROR", "BUDGET_EXHAUSTED")) / total * 100),
                round(count(stats, Set.of("GUARDRAIL_REJECTED", "INVALID_OUTPUT")) / total * 100),
                byTask,
                providers.all().stream()
                        .map(p -> new Circuit(p.id(), guards.state(p.id()).name()))
                        .toList());
    }

    private static TaskLine line(List<RequestStat> rows) {
        var first = rows.get(0);
        var latencies = rows.stream().map(RequestStat::latencyMs).sorted().toList();
        long p95 = latencies.get(Math.max(0, (int) Math.ceil(0.95 * latencies.size()) - 1));
        double n = rows.size();
        return new TaskLine(
                first.task(),
                first.model(),
                rows.size(),
                p95,
                round(rows.stream().filter(r -> !SUCCESS.contains(r.outcome())).count() / n * 100),
                round(rows.stream().filter(RequestStat::cached).count() / n * 100),
                rows.stream().mapToDouble(RequestStat::costInr).sum());
    }

    private static long count(List<RequestStat> stats, Set<String> outcomes) {
        return stats.stream().filter(s -> outcomes.contains(s.outcome())).count();
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
