package com.openhire.aigateway.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.aigateway.application.port.AiPorts.RequestRecord;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import com.openhire.aigateway.domain.AiTypes.Provenance;
import com.openhire.aigateway.domain.SeedPrompts;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Opt-in: creates and drops only a uniquely named oh_phase6_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoAiStoresTest {
    private static MongoClient client;
    private static MongoTemplate mongo;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase6_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void seedingNeverOverwritesAnEditedPrompt() {
        var store = new MongoPromptStore(mongo);
        var seeds = SeedPrompts.forTenant("t1", Instant.parse("2026-10-15T00:00:00Z"));
        seeds.forEach(store::insertIfAbsent);
        var judge = store.find("t1", "match.criterion.judge@1").orElseThrow();
        store.save(judge.withStatus(PromptStatus.RETIRED, 0, Instant.now()));
        seeds.forEach(store::insertIfAbsent);
        assertThat(store.find("t1", "match.criterion.judge@1").orElseThrow().status())
                .isEqualTo(PromptStatus.RETIRED);
        assertThat(store.list("t1")).hasSize(7);
        assertThat(store.list("t2")).isEmpty();
        assertThat(store.versions("t1", "match.criterion.judge")).hasSize(2);
    }

    @Test
    void usageIsAccumulatedAtomicallyPerTenantMonthAndTask() {
        var usage = new MongoUsageStore(mongo);
        usage.add("t1", "2026-10", AiTask.MATCH_JUDGE, 100, 50, 1.5, 200);
        var after = usage.add("t1", "2026-10", AiTask.MATCH_JUDGE, 10, 5, 0.5, 100);
        assertThat(after.costInr()).isEqualTo(2.0);
        assertThat(after.promptTokens()).isEqualTo(110);
        assertThat(after.requests()).isEqualTo(2);
        assertThat(after.byTask().get("MATCH_JUDGE").requests()).isEqualTo(2);
        assertThat(usage.get("t1", "2026-11").costInr()).isZero();
    }

    @Test
    void liveRequestsCarryTheirCompletionEventAndNoPromptText() {
        var log = new MongoRequestLog(mongo, "ai_requests");
        var provenance = new Provenance(
                "req-1",
                "match.judge",
                "ollama",
                "qwen2.5:3b-instruct",
                "match.criterion.judge",
                1,
                900,
                120,
                40,
                0,
                false,
                "trace-1",
                Instant.now());
        log.save(new RequestRecord("t1", provenance, "OK", null, Map.of("grounded", true), "abc", "LIVE", null));
        log.save(new RequestRecord(
                "t1",
                new Provenance("req-2", "match.judge", "ollama", "m", "k", 1, 1, 1, 1, 0, false, "t", Instant.now()),
                "OK",
                null,
                Map.of(),
                "abc",
                "EVAL",
                null));
        var row = mongo.findById("req-1", Document.class, "ai_requests");
        assertThat(((java.util.List<?>) row.get("_events"))).hasSize(1);
        assertThat(((Document) ((java.util.List<?>) row.get("_events")).get(0)).getString("eventType"))
                .isEqualTo("ai.request.completed.v1");
        assertThat(mongo.findById("req-2", Document.class, "ai_requests").get("_events"))
                .isEqualTo(java.util.List.of());
        assertThat(log.since("t1", Instant.now().minusSeconds(60))).hasSize(1);
    }
}
