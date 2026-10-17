package com.openhire.support.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.support.privacy.PrivacyParticipant.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PrivacyHandlerTest {
    private final List<String> erased = new ArrayList<>();

    private PrivacyParticipant participant(String name, Outcome outcome) {
        return new PrivacyParticipant() {
            public String service() {
                return name;
            }

            public Map<String, Object> export(String tenantId, String candidateId) {
                return Map.of("records", 2, "owner", candidateId);
            }

            public Outcome erase(String tenantId, String requestId, String candidateId) {
                erased.add(name + ":" + tenantId + ":" + candidateId + ":" + requestId);
                return outcome;
            }
        };
    }

    private static String request(String type) {
        return """
                {"eventId":"e1","eventType":"%s","tenantId":"t1","payload":{"requestId":"req-1","candidateId":"cand-1"}}
                """
                .formatted(type);
    }

    @Test
    void anErasureRequestIsAnsweredWithTheOutcomeOfEachParticipant() {
        var handler = new PrivacyHandler(
                List.of(
                        participant("application-service", Outcome.erased(Map.of("submissions", 2))),
                        participant("employee-service", Outcome.retained("Employment record", Map.of("employees", 1)))),
                "svc");

        var acks = handler.handle(request(PrivacyHandler.ERASURE_REQUESTED));

        assertThat(erased).containsExactly("application-service:t1:cand-1:req-1", "employee-service:t1:cand-1:req-1");
        assertThat(acks).hasSize(2).allSatisfy(ack -> assertThat(ack.key()).isEqualTo("req-1"));
        var first = new ObjectMapper().readTree(acks.getFirst().json());
        assertThat(first.get("eventType").asString()).isEqualTo("privacy.erasure.completed.v1");
        assertThat(first.get("payload").get("service").asString()).isEqualTo("application-service");
        assertThat(first.get("payload").get("status").asString()).isEqualTo("ERASED");
        assertThat(first.get("payload").get("counts").get("submissions").asInt())
                .isEqualTo(2);
        var second = new ObjectMapper().readTree(acks.get(1).json());
        assertThat(second.get("payload").get("status").asString()).isEqualTo("RETAINED_LEGAL_BASIS");
        assertThat(second.get("payload").get("note").asString()).isEqualTo("Employment record");
    }

    @Test
    void anExportRequestCarriesEachServicesSectionAndErasesNothing() {
        var handler = new PrivacyHandler(List.of(participant("talent-service", Outcome.nothingHeld())), "svc");

        var acks = handler.handle(request(PrivacyHandler.EXPORT_REQUESTED));

        assertThat(erased).isEmpty();
        var payload = new ObjectMapper().readTree(acks.getFirst().json()).get("payload");
        assertThat(payload.get("status").asString()).isEqualTo("EXPORTED");
        assertThat(payload.get("section").get("owner").asString()).isEqualTo("cand-1");
    }

    @Test
    void anOutcomeWithNoRowsCountsAsNothingHeld() {
        assertThat(Outcome.erased(Map.of("a", 0, "b", 0)).status()).isEqualTo(PrivacyParticipant.Status.NOTHING_HELD);
        assertThat(Outcome.erased(Map.of("a", 0, "b", 3)).status()).isEqualTo(PrivacyParticipant.Status.ERASED);
    }

    @Test
    void otherEventsAreIgnoredAndIncompleteRequestsRefused() {
        var handler = new PrivacyHandler(List.of(participant("x", Outcome.nothingHeld())), "svc");

        assertThat(handler.handle(
                        "{\"eventType\":\"compliance.legal-hold.placed.v1\",\"tenantId\":\"t1\",\"payload\":{}}"))
                .isEmpty();
        assertThatThrownBy(
                        () -> handler.handle(
                                "{\"eventType\":\"compliance.erasure.requested.v1\",\"tenantId\":\"t1\",\"payload\":{\"requestId\":\"r\"}}"))
                .hasMessageContaining("needs a tenant");
        assertThat(erased).isEmpty();
    }

    @Test
    void aRedeliveredRequestIsAnsweredAgain() {
        var handler = new PrivacyHandler(List.of(participant("x", Outcome.nothingHeld())), "svc");
        handler.handle(request(PrivacyHandler.ERASURE_REQUESTED));
        handler.handle(request(PrivacyHandler.ERASURE_REQUESTED));
        assertThat(erased)
                .hasSize(2); // participants are idempotent; the orchestrator deduplicates the acknowledgements
    }
}
