package com.openhire.talent.repository.mongo;

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

    private static Document profile(String tenant, String candidate, String name) {
        return new Document("_id", tenant + ":" + candidate)
                .append("tenantId", tenant)
                .append("candidateId", candidate)
                .append("name", name)
                .append("consent", "GRANTED")
                .append("skills", List.of("java"))
                .append("summary", "Senior engineer with Kafka")
                .append("chunks", List.of(new Document("chunkId", "c1").append("text", "Led a migration")))
                .append("revision", 3L);
    }

    @BeforeEach
    void rows() {
        mongo.put("talent_profiles", profile("t1", "cand-1", "Priya Das"));
        mongo.put("talent_profiles", profile("t1", "cand-2", "Ben Other"));
        mongo.put("talent_profiles", profile("t2", "cand-1", "Priya Das"));
        for (var id : List.of("t1:cand-1", "t1:cand-2", "t2:cand-1"))
            mongo.put("talent_profile_vectors", new Document("_id", id).append("vector", List.of(0.1, 0.2)));
        mongo.put(
                "talent_invitations",
                new Document("_id", "i1")
                        .append("tenantId", "t1")
                        .append("candidateId", "cand-1")
                        .append("jobTitle", "Engineer")
                        .append("actorId", "staff-1"));
        mongo.put(
                "talent_invitations",
                new Document("_id", "i2").append("tenantId", "t1").append("candidateId", "cand-2"));
        mongo.put(
                "rediscovery_runs",
                new Document("_id", "run-1")
                        .append("tenantId", "t1")
                        .append("jobTitle", "Staff Engineer")
                        .append(
                                "matches",
                                List.of(
                                        new Document("candidateId", "cand-1")
                                                .append("name", "Priya Das")
                                                .append("whyFitNow", "Fits well"),
                                        new Document("candidateId", "cand-2").append("name", "Ben Other"))));
        mongo.put(
                "rediscovery_runs",
                new Document("_id", "run-2")
                        .append("tenantId", "t1")
                        .append("matches", List.of(new Document("candidateId", "cand-2").append("name", "Ben Other"))));
    }

    @Test
    void exportShowsTheProfileInvitationsAndRediscoveryResultsOfThisCandidateOnly() {
        var section = data.export("t1", "cand-1");

        var text = section.toString();
        assertThat(text)
                .contains("Senior engineer with Kafka")
                .contains("Staff Engineer")
                .contains("Fits well");
        assertThat(text).doesNotContain("Ben Other").doesNotContain("staff-1").doesNotContain("0.1");
        assertThat(section.get("searchVectorStored")).isEqualTo(true);
        assertThat((List<?>) section.get("invitations")).hasSize(1);
        assertThat(((Map<?, ?>) section.get("profile")).keySet().stream().map(Object::toString))
                .doesNotContain("_id", "revision", "tenantId");
    }

    @Test
    void erasureDeletesProfileVectorInvitationsAndRediscoveryResults() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts())
                .containsEntry("profiles", 1)
                .containsEntry("vectors", 1)
                .containsEntry("invitations", 1)
                .containsEntry("rediscoveryRuns", 1);
        assertThat(mongo.one("talent_profiles", "t1:cand-1")).isNull();
        assertThat(mongo.one("talent_profile_vectors", "t1:cand-1")).isNull();
        assertThat(mongo.one("talent_invitations", "i1")).isNull();
        var run = mongo.one("rediscovery_runs", "run-1");
        assertThat(run.getList("matches", Document.class))
                .extracting(m -> m.getString("candidateId"))
                .containsExactly("cand-2");
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(mongo.one("talent_profiles", "t1:cand-2")).isNotNull();
        assertThat(mongo.one("talent_profiles", "t2:cand-1")).isNotNull();
        assertThat(mongo.one("talent_profile_vectors", "t2:cand-1")).isNotNull();
        assertThat(mongo.one("talent_invitations", "i2")).isNotNull();
        assertThat(mongo.one("rediscovery_runs", "run-2").getList("matches", Document.class))
                .hasSize(1);
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(again.counts().values()).containsOnly(0);
    }
}
