package com.openhire.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.platform.audit.AuditRecorder.Outbox;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditRecorderTest {
    private static final class MemoryOutbox implements Outbox {
        final Map<String, Pending> rows = new LinkedHashMap<>();

        public void add(String eventId, String tenantId, String json) {
            rows.put(eventId, new Pending(eventId, tenantId, json));
        }

        public List<Pending> pending(int limit) {
            return rows.values().stream().limit(limit).toList();
        }

        public void remove(String eventId) {
            rows.remove(eventId);
        }
    }

    private final MemoryOutbox outbox = new MemoryOutbox();
    private final List<String> sent = new ArrayList<>();
    private boolean kafkaUp = true;
    private final AuditRecorder recorder = new AuditRecorder(
            outbox,
            (key, json) -> {
                if (!kafkaUp) throw new IllegalStateException("broker down");
                sent.add(key + "|" + json);
            },
            "offer-service",
            true);

    private AuditRecord sample() {
        return AuditRecord.of("t1", "OFFER_SENT")
                .user("sub-1", "Arjun Mehta")
                .resource("Offer", "offer_1")
                .about("cand-1", "app-1")
                .detail("versionNo", 2)
                .detail("reasonCode", "OK")
                .build();
    }

    @Test
    void anActionBecomesAnEnvelopeWithActorResourceAndTrace() throws Exception {
        recorder.record(sample());

        assertThat(recorder.drain()).isEqualTo(1);
        var json = new ObjectMapper()
                .readTree(sent.getFirst().substring(sent.getFirst().indexOf('|') + 1));
        assertThat(sent.getFirst()).startsWith("t1|");
        assertThat(json.get("eventType").asString()).isEqualTo("audit.event.recorded.v1");
        assertThat(json.get("producer").asString()).isEqualTo("offer-service");
        assertThat(json.get("correlationId").asString()).isEqualTo("app-1");
        var payload = json.get("payload");
        assertThat(payload.get("action").asString()).isEqualTo("OFFER_SENT");
        assertThat(payload.get("actor").get("id").asString()).isEqualTo("sub-1");
        assertThat(payload.get("actor").get("name").asString()).isEqualTo("Arjun Mehta");
        assertThat(payload.get("resource").get("id").asString()).isEqualTo("offer_1");
        assertThat(payload.get("candidateId").asString()).isEqualTo("cand-1");
        assertThat(payload.get("details").get("versionNo").asInt()).isEqualTo(2);
        assertThat(outbox.rows).isEmpty();
    }

    @Test
    void recordsWaitInTheOutboxWhileTheBrokerIsDownAndAreRelayedOnceItIsBack() throws Exception {
        kafkaUp = false;
        recorder.record(sample());
        recorder.record(sample());

        assertThatThrownBy(recorder::drain).hasMessageContaining("broker down");
        assertThat(outbox.rows).hasSize(2);

        kafkaUp = true;
        assertThat(recorder.drain()).isEqualTo(2);
        assertThat(sent).hasSize(2);
        assertThat(outbox.rows).isEmpty();
    }

    @Test
    void personalDataKeysNeverReachTheAuditTrail() {
        var record = AuditRecord.of("t1", "MESSAGE_SENT")
                .resource("Conversation", "c1")
                .detail("candidateEmail", "p@example.test")
                .detail("phone", "+919800000000")
                .detail("messageText", "hello")
                .detail("channel", "EMAIL")
                .detail("attempts", 2)
                .build();

        assertThat(record.details()).containsOnlyKeys("channel", "attempts");
    }

    @Test
    void badRecordsAreRejectedAtTheCallSite() {
        assertThatThrownBy(() -> AuditRecord.of("t1", "offer sent")
                        .resource("Offer", "o")
                        .build())
                .hasMessageContaining("upper-case");
        assertThatThrownBy(() ->
                        AuditRecord.of("", "OFFER_SENT").resource("Offer", "o").build())
                .hasMessageContaining("tenant");
        assertThatThrownBy(() -> AuditRecord.of("t1", "OFFER_SENT").build()).hasMessageContaining("resource");
    }

    @Test
    void aFailureToStoreNeverBreaksTheCaller() {
        var broken = new AuditRecorder(
                new Outbox() {
                    public void add(String eventId, String tenantId, String json) {
                        throw new IllegalStateException("mongo down");
                    }

                    public List<Pending> pending(int limit) {
                        return List.of();
                    }

                    public void remove(String eventId) {}
                },
                (key, json) -> {},
                "x",
                true);

        broken.record(sample()); // logged loudly, not thrown
    }

    @Test
    void aDisabledRecorderDoesNothing() throws Exception {
        var off = new AuditRecorder(outbox, (key, json) -> sent.add(json), "x", false);
        off.record(sample());
        assertThat(outbox.rows).isEmpty();
        assertThat(sent).isEmpty();
    }
}
