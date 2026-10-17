package com.openhire.analytics.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.analytics.application.port.AnalyticsPorts.Replay;
import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.Facts;
import com.openhire.analytics.domain.Facts.AiSample;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.DecisionKind;
import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.ServiceSample;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Opt-in: creates and drops only a uniquely named oh_phase7_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoProjectionStoresTest {
    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");
    private static MongoClient client;
    private static MongoTemplate mongo;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase7_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void anEventIdIsClaimedOnceAndCanBeReleased() {
        var processed = new MongoProcessedEvents(mongo);

        assertThat(processed.claim("application", "e1", T)).isTrue();
        assertThat(processed.claim("application", "e1", T)).isFalse();
        assertThat(processed.claim("offer", "e1", T)).isTrue();
        processed.release("application", "e1");
        assertThat(processed.claim("application", "e1", T)).isTrue();
    }

    @Test
    void applicationFactsRoundTripAndAreFoundByTenantJobAndActivity() {
        var facts = new MongoApplicationFacts(mongo);
        var fact = ApplicationFact.empty("t1", "a1")
                .withJob("j1", "Engineer", "CAREER")
                .withSubmitted(T)
                .withStage("SHORTLISTED", T.plusSeconds(60))
                .withStage("HIRED", T.plusSeconds(120));
        facts.save(fact);
        facts.save(
                ApplicationFact.empty("t2", "a1").withJob("j1", null, "AGENCY").withSubmitted(T));

        assertThat(facts.find("t1", "a1")).contains(fact);
        assertThat(facts.touchedSince("t1", null, T)).containsExactly(fact);
        assertThat(facts.touchedSince("t1", "other", T)).isEmpty();
        assertThat(facts.touchedSince("t1", "j1", T.plusSeconds(121))).isEmpty();
        assertThat(facts.find("t1", "missing")).isEmpty();
    }

    @Test
    void decisionsAreAddedOnceAndTheLatestCanBeUndone() {
        var decisions = new MongoDecisionFacts(mongo);
        var first = decision("d1", DecisionKind.SHORTLIST, T);
        var second = decision("d2", DecisionKind.REJECT, T.plusSeconds(10));
        var override = decision("d3", DecisionKind.OVERRIDE, T.plusSeconds(20));

        assertThat(decisions.add(first)).isTrue();
        assertThat(decisions.add(first)).isFalse();
        decisions.add(second);
        decisions.add(override);
        decisions.markLatestUndone("t1", "a1");

        var stored = decisions.between("t1", T.minusSeconds(1), T.plusSeconds(60));
        assertThat(stored).hasSize(3);
        assertThat(stored)
                .filteredOn(DecisionFact::undone)
                .extracting(DecisionFact::id)
                .containsExactly("d2");
        assertThat(decisions.between("t1", T, T.plusSeconds(15)))
                .extracting(DecisionFact::id)
                .containsExactly("d2");
        assertThat(decisions.between("t2", T.minusSeconds(1), T.plusSeconds(60)))
                .isEmpty();
    }

    private static DecisionFact decision(String id, DecisionKind kind, Instant at) {
        return new DecisionFact(
                "t1", id, "a1", "j1", kind, "USER", "u1", at, "APPLIED", "SHORTLISTED", "OTHER", null, false);
    }

    @Test
    void aiRequestsAccumulateAtomicallyIntoTheirDay() {
        var days = new MongoAiDayFacts(mongo);
        var day = LocalDate.parse("2026-10-01");
        for (int i = 0; i < 20; i++) days.add(new AiSample("t1", day, "judge", "qwen", i == 0, 10, 5, 0.5, 100));
        days.add(new AiSample("t1", day, "judge", "qwen", false, 0, 0, 0, 25_000));

        var rows = days.between("t1", day, day);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.requests()).isEqualTo(21);
            assertThat(row.errors()).isEqualTo(1);
            assertThat(row.promptTokens()).isEqualTo(200);
            assertThat(row.costInr()).isEqualTo(10.0);
            assertThat(row.latencyBuckets()[Facts.bucket(100)]).isEqualTo(20);
            assertThat(row.p95Ms()).isEqualTo(100);
        });
        assertThat(days.between("t1", day.plusDays(1), day.plusDays(2))).isEmpty();
    }

    @Test
    void latencyEdgesCombineInEitherOrder() {
        var latencies = new MongoLatencyFacts(mongo);
        latencies.mark("t1", LatencyKind.NOTIFICATION_HANDOFF, "m1", Edge.END, T.plusSeconds(7));
        latencies.mark("t1", LatencyKind.NOTIFICATION_HANDOFF, "m1", Edge.START, T);
        latencies.mark("t1", LatencyKind.NOTIFICATION_HANDOFF, "m1", Edge.END, T.plusSeconds(30));
        latencies.mark("t1", LatencyKind.NOTIFICATION_HANDOFF, "open", Edge.START, T.plusSeconds(1));

        var all = latencies.startedSince("t1", LatencyKind.NOTIFICATION_HANDOFF, T);

        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(f -> f.key().equals("m1")).singleElement().satisfies(f -> assertThat(f.duration())
                .contains(Duration.ofSeconds(7)));
        assertThat(latencies.startedSince("t2", LatencyKind.NOTIFICATION_HANDOFF, T))
                .isEmpty();
        assertThat(latencies.startedSince(null, LatencyKind.NOTIFICATION_HANDOFF, T))
                .hasSize(2);
    }

    @Test
    void aReplayIsRecordedOnceAndCountedPerTenant() {
        var log = new MongoReplayLog(mongo);
        var replay = new Replay("t.DLT:0:1", "t1", "t", "reason here", "u1", T, 1, false);

        assertThat(log.insert(replay)).isTrue();
        assertThat(log.insert(replay)).isFalse();
        log.update(new Replay("t.DLT:0:1", "t1", "t", "again here", "root", T.plusSeconds(5), 2, true));

        assertThat(log.find("t.DLT:0:1"))
                .hasValueSatisfying(r -> assertThat(r.count()).isEqualTo(2));
        assertThat(log.findAll(java.util.List.of("t.DLT:0:1", "x"))).hasSize(1);
        assertThat(log.since("t1", T)).containsExactly(T.plusSeconds(5));
        assertThat(log.since("t2", T)).isEmpty();
        log.delete("t.DLT:0:1");
        assertThat(log.find("t.DLT:0:1")).isEmpty();
    }

    @Test
    void healthSamplesAreReadBackNewestAndInRange() {
        var store = new MongoHealthStore(mongo);
        store.add(new ServiceSample("svc", T, true, "UP", 10, 100, 5.0, 1, 0, 0, 0, 0.2));
        store.add(new ServiceSample("svc", T.plusSeconds(15), false, "UNREACHABLE", 3000, -1, 0, -1, 0, 0, 0, 0));

        assertThat(store.latest("svc"))
                .hasValueSatisfying(s -> assertThat(s.up()).isFalse());
        assertThat(store.latest("svc").orElseThrow().hasMetrics()).isFalse();
        assertThat(store.since("svc", T).getFirst().requestCount()).isEqualTo(100);
        assertThat(store.since("svc", T.plusSeconds(1))).hasSize(1);
        assertThat(store.latest("other")).isEmpty();
    }
}
