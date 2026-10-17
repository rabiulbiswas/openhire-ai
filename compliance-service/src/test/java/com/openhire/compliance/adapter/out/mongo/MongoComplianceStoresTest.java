package com.openhire.compliance.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.compliance.application.AuditLedger;
import com.openhire.compliance.domain.AuditEntry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.ObjectMapper;

/** Opt-in: creates and drops only a uniquely named oh_phase7_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoComplianceStoresTest {
    private static MongoClient client;
    private static MongoTemplate mongo;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase7_test_" + UUID.randomUUID().toString().replace("-", ""));
        new MongoIndexes(mongo, "compliance_events").create();
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    private static Map<String, Object> event(String tenant, String eventId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "OFFER_SENT");
        payload.put("actor", Map.of("type", "USER", "id", "u1", "name", "Asha"));
        payload.put("resource", Map.of("type", "Offer", "id", "off_1"));
        payload.put("candidateId", "cand_1");
        payload.put("details", Map.of("versionNo", 2, "ratio", 0.5, "flag", true));
        payload.put("at", "2026-10-01T10:00:00Z");
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", eventId);
        event.put("producer", "offer-service");
        event.put("eventType", AuditLedger.EVENT_TYPE);
        event.put("tenantId", tenant);
        event.put("payload", payload);
        return event;
    }

    @Test
    void auditArchivalIsBoundedRepeatableAndPreservesTheHashChain() {
        var ledger = new AuditLedger(new MongoAuditStore(mongo), record -> {}, Clock.systemUTC());
        for (int i = 0; i < 3; i++) ledger.ingest(event("archive-test", "archive-" + i));
        ledger.ingest(event("archive-other", "archive-other"));
        var runner = new MongoRecordRetention(
                mongo, new com.openhire.support.Access("local", "demo", "test"), "http://localhost:1");
        assertThat(runner.run("archive-test", "audit.events", "2027-01-01T00:00:00.000Z", true, 2))
                .containsEntry("processed", 0)
                .containsEntry("eligibleInBatch", 2);
        assertThat(mongo.getCollection("audit_archive").countDocuments()).isZero();
        assertThat(runner.run("archive-test", "audit.events", "2027-01-01T00:00:00.000Z", false, 2))
                .containsEntry("processed", 2);
        assertThat(runner.run("archive-test", "audit.events", "2027-01-01T00:00:00.000Z", false, 2))
                .containsEntry("processed", 1);
        assertThat(runner.run("archive-test", "audit.events", "2027-01-01T00:00:00.000Z", false, 2))
                .containsEntry("processed", 0);
        assertThat(ledger.verify("archive-test").valid()).isTrue();
        assertThat(ledger.ingest(event("archive-test", "archive-0"))).isFalse();
        assertThat(mongo.getCollection("audit_archive").countDocuments()).isEqualTo(3);
    }

    @Test
    void concurrentAppendsGetAGaplessChainThatSurvivesTheDatabaseRoundTrip() throws Exception {
        var ledger = new AuditLedger(new MongoAuditStore(mongo), record -> {}, Clock.systemUTC());
        var pool = Executors.newFixedThreadPool(8);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 40; i++) {
            var id = "evt-" + i;
            tasks.add(() -> ledger.ingest(event("t1", id)));
        }
        for (var f : pool.invokeAll(tasks)) assertThat(f.get()).isTrue();
        pool.shutdown();

        var rows = new MongoAuditStore(mongo).after("t1", 0, null, null, 100);
        assertThat(rows)
                .extracting(AuditEntry::seq)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList());
        assertThat(ledger.verify("t1").valid()).isTrue();
        assertThat(ledger.ingest(event("t1", "evt-3")))
                .as("redelivery is ignored")
                .isFalse();
        assertThat(ledger.list(
                                "t1",
                                com.openhire.compliance.application.port.CompliancePorts.AuditFilter.none(),
                                null,
                                5)
                        .data())
                .hasSize(5);
    }

    @Test
    void anAcknowledgementIsRecordedOnceAndSectionsMayHaveAnyKeys() {
        var store = new MongoPrivacyStore(mongo, new ObjectMapper());
        var request = new LinkedHashMap<String, Object>();
        request.put("_id", "prv_1");
        request.put("tenantId", "t1");
        request.put("candidateId", "cand_1");
        request.put("status", "IN_PROGRESS");
        request.put("createdAt", "2026-10-01T10:00:00.000Z");
        request.put("participants", Map.of("application-service", Map.of("status", "PENDING")));
        store.insert(request);

        var entry = Map.<String, Object>of("status", "EXPORTED", "at", "2026-10-01T10:01:00.000Z");
        var section = Map.<String, Object>of("a.b", 1, "$odd", List.of("x"));
        assertThat(store.recordAck("t1", "prv_1", "application-service", entry, section))
                .isPresent();
        assertThat(store.recordAck("t1", "prv_1", "application-service", entry, section))
                .isEmpty();
        assertThat(store.recordAck("t2", "prv_1", "application-service", entry, section))
                .isEmpty();
        assertThat(store.export("t1", "prv_1").orElseThrow().get("sections").toString())
                .contains("a.b");

        store.anonymise("t1", "cand_1", "hash");
        assertThat(store.find("t1", "prv_1").orElseThrow())
                .doesNotContainKey("candidateId")
                .containsEntry("candidateIdHash", "hash");
        assertThat(store.export("t1", "prv_1")).isEmpty();
    }

    @Test
    void activitySavesAreOptimistic() {
        var store = new MongoActivityStore(mongo);
        var doc = new LinkedHashMap<String, Object>();
        doc.put("_id", "t1|app_1");
        doc.put("tenantId", "t1");
        doc.put("applicationId", "app_1");
        doc.put("candidateId", "cand_1");
        doc.put("lastActivityAt", "2024-01-01T00:00:00.000Z");
        doc.put("version", 0L);
        assertThat(store.save(doc)).isTrue();
        var stale = store.find("t1", "app_1").orElseThrow();
        var fresh = store.find("t1", "app_1").orElseThrow();
        assertThat(store.save(fresh)).isTrue();
        assertThat(store.save(stale)).isFalse();
        assertThat(store.save(new LinkedHashMap<>(doc) {
                    {
                        put("version", 0L);
                    }
                }))
                .isFalse();
        var active = new LinkedHashMap<String, Object>(doc);
        active.put("_id", "t1|app_2");
        active.put("applicationId", "app_2");
        active.put("lifecycle", "ACTIVE");
        active.put("version", 0L);
        assertThat(store.save(active)).isTrue();
        assertThat(store.idleSince("t1", "2025-01-01T00:00:00.000Z", 10)).hasSize(1);
        assertThat(store.tenants()).contains("t1");
    }

    @Test
    void theOutboxHoldsOneDocumentPerEventForTheSharedRelay() {
        new MongoEvents(mongo, "compliance_events")
                .publish(
                        "t1",
                        "PrivacyRequest",
                        "prv_1",
                        "compliance.erasure.requested.v1",
                        Map.of("requestId", "prv_1"));
        var row = mongo.getCollection("compliance_events").find().first();
        assertThat(row).isNotNull();
        assertThat((List<?>) row.get("_events")).hasSize(1);
    }
}
