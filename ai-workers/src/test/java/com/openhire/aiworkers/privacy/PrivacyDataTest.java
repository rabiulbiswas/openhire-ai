package com.openhire.aiworkers.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyDataTest {
    private final InMemoryMongo mongo = new InMemoryMongo();
    private final com.openhire.support.privacy.Tombstones tombstones = InMemoryMongo.tombstones();
    private final PrivacyData data = new PrivacyData(mongo.template, tombstones);

    private Document owned(String collection, String id, String tenant, String candidate) {
        return mongo.put(
                collection, new Document("_id", id).append("tenantId", tenant).append("candidateId", candidate));
    }

    @BeforeEach
    void rows() {
        for (var owner : List.of(List.of("t1", "cand-1"), List.of("t1", "cand-2"), List.of("t2", "cand-1"))) {
            var key = owner.get(0) + owner.get(1);
            owned("resume_chunks", "ch-" + key, owner.get(0), owner.get(1))
                    .append("text", "Led a Kafka migration")
                    .append("vector", List.of(0.1, 0.2))
                    .append("section", "experience")
                    .append("resumeId", "res-1");
            owned("assessments", "as-" + key, owner.get(0), owner.get(1))
                    .append("applicationId", "app-" + key)
                    .append("score", 80)
                    .append("status", "COMPLETE")
                    .append("commandEventId", "cmd")
                    .append(
                            "payload",
                            new Document("criteria", List.of(new Document("evidence", List.of("quoted line")))));
            owned("integrity_signals", "sig-" + key, owner.get(0), owner.get(1))
                    .append("type", "TIMELINE_OVERLAP")
                    .append("evidence", "Two roles overlap")
                    .append("dismissedBy", "staff-1");
            owned("identity_index", "id-" + key, owner.get(0), owner.get(1)).append("valueHash", "abc");
            mongo.put(
                    "integrity_dismissals",
                    new Document("tenantId", owner.get(0))
                            .append("signalId", "sig-" + key)
                            .append("reason", "checked"));
            mongo.put(
                    "matching_retries",
                    new Document("_id", "retry-" + key)
                            .append("tenantId", owner.get(0))
                            .append("request", new Document("payload", new Document("candidateId", owner.get(1)))));
        }
        mongo.put("criterion_vectors", new Document("_id", "cv1").append("vector", List.of(0.5)));
    }

    @Test
    void exportHasQuotesAndSignalsButNoVectorsHashesOrStaffIds() {
        var section = data.export("t1", "cand-1");

        var text = section.toString();
        assertThat(text)
                .contains("Led a Kafka migration")
                .contains("quoted line")
                .contains("Two roles overlap");
        assertThat(text)
                .doesNotContain("0.1")
                .doesNotContain("abc")
                .doesNotContain("staff-1")
                .doesNotContain("cmd");
        assertThat((List<?>) section.get("resumeChunks")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) section.get("assessments")).getFirst()).get("score"))
                .isEqualTo(80);
    }

    @Test
    void erasureDeletesEverythingDerivedFromThisCandidateAndKeepsCriterionVectors() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts())
                .containsEntry("resumeChunks", 1)
                .containsEntry("assessments", 1)
                .containsEntry("integritySignals", 1)
                .containsEntry("integrityDismissals", 1)
                .containsEntry("identityIndexEntries", 1)
                .containsEntry("matchingRetries", 1);
        assertThat(mongo.one("resume_chunks", "ch-t1cand-1")).isNull();
        assertThat(mongo.one("assessments", "as-t1cand-1")).isNull();
        assertThat(mongo.one("matching_retries", "retry-t1cand-1")).isNull();
        assertThat(mongo.collection("criterion_vectors")).hasSize(1);
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        for (var collection :
                List.of("resume_chunks", "assessments", "integrity_signals", "identity_index", "matching_retries"))
            assertThat(mongo.collection(collection)).hasSize(2);
        assertThat(mongo.collection("integrity_dismissals")).hasSize(2);
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(again.counts().values()).containsOnly(0);
    }
}
