package com.openhire.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.ServiceSample;
import com.openhire.analytics.dto.PlatformViews.ConsumerLag;
import com.openhire.analytics.service.Memory.Health;
import com.openhire.analytics.service.Memory.MutableClock;
import com.openhire.analytics.service.Memory.Store;
import com.openhire.analytics.service.port.AnalyticsPorts.ConsumerOffsets.PartitionOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlatformQueryServiceTest {
    private final Instant now = Instant.parse("2026-10-15T12:00:00Z");
    private final MutableClock clock = new MutableClock(now);
    private final Health health = new Health();
    private final Store store = new Store();
    private List<PartitionOffset> offsets = List.of();
    private final PlatformQueryService platform = new PlatformQueryService(
            PlatformSettings.defaults(new LinkedHashMap<>(java.util.Map.of("application-service", "http://a"))),
            health,
            store,
            () -> offsets,
            clock);

    private PlatformQueryService withTargets(String... names) {
        var targets = new LinkedHashMap<String, String>();
        for (var name : names) targets.put(name, "http://" + name);
        return new PlatformQueryService(PlatformSettings.defaults(targets), health, store, () -> offsets, clock);
    }

    private void poll(
            String service,
            int minutesAgo,
            boolean up,
            String status,
            long ms,
            long requests,
            long errors,
            double seconds,
            double max) {
        health.add(new ServiceSample(
                service,
                now.minus(Duration.ofMinutes(minutesAgo)),
                up,
                status,
                ms,
                requests >= 0 ? 1000 : -1,
                0,
                0,
                Math.max(0, requests),
                errors,
                seconds,
                max));
    }

    private void units(LatencyKind kind, String tenant, String prefix, int count, long seconds) {
        for (int i = 0; i < count; i++) {
            var start = now.minus(Duration.ofHours(1));
            store.mark(tenant, kind, prefix + i, Edge.START, start);
            store.mark(tenant, kind, prefix + i, Edge.END, start.plusSeconds(seconds));
        }
    }

    @Test
    void availabilityIsTheShareOfUpPollsAndTheBudgetShowsWhatIsLeft() {
        for (int i = 0; i < 399; i++) poll("application-service", i + 1, true, "UP", 20, -1, 0, 0, 0);
        poll("application-service", 500, false, "UNREACHABLE", 3000, -1, 0, 0, 0);

        var slo = platform.slo("t1").getFirst();

        assertThat(slo.id()).isEqualTo("public-api-availability");
        assertThat(slo.current()).isEqualTo(99.75);
        assertThat(slo.target()).isEqualTo(99.5);
        assertThat(slo.window()).isEqualTo("24h");
        assertThat(slo.status()).isEqualTo("MET");
        assertThat(slo.errorBudgetRemainingPct()).isEqualTo(50.0);
        assertThat(slo.basis()).contains("399 of 400");
        assertThat(slo.approximate()).isFalse();
    }

    @Test
    void moreThanOnePercentDownBreachesAvailability() {
        for (int i = 0; i < 99; i++) poll("application-service", i + 1, true, "UP", 20, -1, 0, 0, 0);
        poll("application-service", 200, false, "DOWN", 20, -1, 0, 0, 0);

        var slo = platform.slo("t1").getFirst();

        assertThat(slo.current()).isEqualTo(99.0);
        assertThat(slo.status()).isEqualTo("BREACHED");
        assertThat(slo.errorBudgetRemainingPct()).isZero();
    }

    @Test
    void latencyIsTheMeanOverPolledRequestsAndIsFlaggedApproximate() {
        poll("application-service", 10, true, "UP", 20, 100, 0, 20, 0.4);
        poll("application-service", 5, true, "UP", 20, 100, 0, 40, 1.2);
        poll("application-service", 1, true, "UP", 20, 0, 0, 0, 0.1);

        var slo = platform.slo("t1").get(1);

        assertThat(slo.id()).isEqualTo("application-api-latency");
        assertThat(slo.current()).isEqualTo(300.0);
        assertThat(slo.unit()).isEqualTo("ms");
        assertThat(slo.status()).isEqualTo("MET");
        assertThat(slo.errorBudgetRemainingPct()).isEqualTo(40.0);
        assertThat(slo.approximate()).isTrue();
        assertThat(slo.basis()).contains("max 1200 ms").contains("approximate");
    }

    @Test
    void eventTimingSlosCountSlowAndOverdueUnitsAgainstTheLimit() {
        units(LatencyKind.RESUME_PROCESSING, "t1", "ok", 19, 20);
        units(LatencyKind.RESUME_PROCESSING, "t1", "slow", 1, 90);
        // Still running but within the limit: not counted yet.
        store.mark("t1", LatencyKind.RESUME_PROCESSING, "running", Edge.START, now.minusSeconds(10));
        units(LatencyKind.AI_ASSESSMENT, "t1", "ai", 9, 100);
        store.mark("t1", LatencyKind.AI_ASSESSMENT, "stuck", Edge.START, now.minus(Duration.ofMinutes(10)));
        units(LatencyKind.NOTIFICATION_HANDOFF, "t1", "n", 100, 5);
        units(LatencyKind.NOTIFICATION_HANDOFF, "t1", "edge", 1, 60);

        var slos = platform.slo("t1");

        var resume = slos.get(2);
        assertThat(resume.id()).isEqualTo("resume-processing-60s");
        assertThat(resume.current()).isEqualTo(95.0);
        assertThat(resume.status()).isEqualTo("AT_RISK");
        assertThat(resume.errorBudgetRemainingPct()).isZero();
        assertThat(resume.approximate()).isTrue();
        var ai = slos.get(3);
        assertThat(ai.id()).isEqualTo("ai-assessment-2m");
        assertThat(ai.current()).isEqualTo(90.0);
        assertThat(ai.status()).isEqualTo("AT_RISK");
        var notification = slos.get(4);
        assertThat(notification.id()).isEqualTo("notification-handoff-60s");
        assertThat(notification.current()).isEqualTo(100.0);
        assertThat(notification.status()).isEqualTo("MET");
        assertThat(notification.errorBudgetRemainingPct()).isEqualTo(100.0);
        assertThat(slos)
                .extracting("id")
                .containsExactly(
                        "public-api-availability",
                        "application-api-latency",
                        "resume-processing-60s",
                        "ai-assessment-2m",
                        "notification-handoff-60s");
    }

    @Test
    void anOrganisationOnlySeesItsOwnTimingsAndASuperAdminSeesAll() {
        units(LatencyKind.RESUME_PROCESSING, "t1", "a", 10, 10);
        units(LatencyKind.RESUME_PROCESSING, "t2", "b", 10, 300);

        assertThat(platform.slo("t1").get(2).current()).isEqualTo(100.0);
        assertThat(platform.slo("t2").get(2).current()).isZero();
        assertThat(platform.slo("t2").get(2).status()).isEqualTo("BREACHED");
        assertThat(platform.slo(null).get(2).current()).isEqualTo(50.0);
    }

    @Test
    void withoutDataEveryObjectiveIsMetWithTheBudgetUntouched() {
        assertThat(platform.slo("t1")).hasSize(5).allSatisfy(slo -> {
            assertThat(slo.status()).isEqualTo("MET");
            assertThat(slo.errorBudgetRemainingPct()).isEqualTo(100.0);
            assertThat(slo.basis()).startsWith("No data yet");
        });
    }

    @Test
    void serviceStatusUptimeAndRequestCounts() {
        var platform = withTargets(
                "up-service", "never-polled", "down-service", "sick-service", "slow-service", "erroring-service");
        for (int i = 0; i < 4; i++) poll("up-service", 10 - i, true, "UP", 40, 100, 1, 1, 0.1);
        poll("up-service", 1, false, "UNREACHABLE", 3000, -1, 0, 0, 0);
        poll("up-service", 0, true, "UP", 42, 50, 2, 1, 0.1);
        poll("down-service", 0, false, "UNREACHABLE", 3000, -1, 0, 0, 0);
        poll("sick-service", 0, false, "OUT_OF_SERVICE", 30, -1, 0, 0, 0);
        poll("slow-service", 0, true, "UP", 3000, -1, 0, 0, 0);
        poll("erroring-service", 0, true, "UP", 30, 100, 10, 1, 0.1);

        var services = platform.services();

        assertThat(services)
                .extracting("name")
                .containsExactly(
                        "up-service",
                        "never-polled",
                        "down-service",
                        "sick-service",
                        "slow-service",
                        "erroring-service");
        assertThat(services)
                .extracting("status")
                .containsExactly("UP", "DOWN", "DOWN", "DEGRADED", "DEGRADED", "DEGRADED");
        var up = services.getFirst();
        assertThat(up.uptimePct24h()).isEqualTo(83.33);
        assertThat(up.responseMs()).isEqualTo(42);
        assertThat(up.requests24h()).isEqualTo(450);
        assertThat(up.errors24h()).isEqualTo(6);
        assertThat(up.lastCheckedAt()).isEqualTo(now.toString());
        assertThat(services.get(1).lastCheckedAt()).isNull();
        assertThat(services.get(4).requests24h()).isNull();
    }

    @Test
    void consumerLagSumsPartitionsPerGroupAndTopic() {
        offsets = List.of(
                new PartitionOffset("g1", "application.events.v1", 0, 5, 10),
                new PartitionOffset("g1", "application.events.v1", 1, 10, 10),
                new PartitionOffset("g2", "application.events.v1", 0, 0, 150),
                new PartitionOffset("g2", "offer.events.v1", 0, 12, 10),
                new PartitionOffset("g2", "offer.events.v1", 1, 100, 100),
                new PartitionOffset("g0", "ai.events.v1", 0, 100, 200));

        assertThat(platform.consumers())
                .containsExactly(
                        new ConsumerLag("g0", "ai.events.v1", 100, "OK"),
                        new ConsumerLag("g1", "application.events.v1", 5, "OK"),
                        new ConsumerLag("g2", "application.events.v1", 150, "LAGGING"),
                        new ConsumerLag("g2", "offer.events.v1", 0, "OK"));
        offsets = List.of();
        assertThat(platform.consumers()).isEmpty();
    }
}
