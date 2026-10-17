package com.openhire.candidateservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openhire.platform.audit.AuditRecorder;
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

    private static Document candidate(String id, String tenant, String name) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("name", name)
                .append("normalizedName", name.toLowerCase())
                .append("email", "x@example.test")
                .append("phone", "+91 1")
                .append("owner", "staff-1")
                .append("skills", List.of("Java"))
                .append("experience", List.of(new Document("title", "Dev at Acme")))
                .append("documents", new Document("r1", new Document("id", "r1").append("name", "cv.pdf")))
                .append(
                        "applications",
                        new Document("app-1", new Document("applicationId", "app-1").append("stage", "APPLIED")))
                .append("timeline", new Document())
                .append("provenance", new Document("name", new Document("source", "CAREER")))
                .append("_events", List.of(new Document("eventId", "e1")))
                .append("mergeLock", "op");
    }

    @BeforeEach
    void rows() {
        mongo.put("candidates", candidate("cand-1", "t1", "Asha Rao"));
        mongo.put("candidates", candidate("cand-2", "t1", "Ben Other"));
        mongo.put("candidates", candidate("cand-1", "t2", "Asha Rao").append("email", "other-tenant@example.test"));
        mongo.put("candidates", candidate("cand-0", "t1", "Asha R").append("mergedInto", "cand-1"));
        mongo.put(
                "merge_suggestions",
                new Document("_id", "s1")
                        .append("tenantId", "t1")
                        .append("a", "cand-0")
                        .append("b", "cand-1"));
        mongo.put(
                "merge_suggestions",
                new Document("_id", "s2")
                        .append("tenantId", "t1")
                        .append("a", "cand-2")
                        .append("b", "cand-3"));
        mongo.put("merge_dismissals", new Document("_id", "s1").append("tenantId", "t1"));
    }

    @Test
    void exportHasTheProfileWithoutInternalFields() {
        var section = data.export("t1", "cand-1");

        var profiles = (List<?>) section.get("profiles");
        assertThat(profiles).hasSize(2);
        var profile = (Map<?, ?>) profiles.stream()
                .filter(p -> "cand-1".equals(((Map<?, ?>) p).get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(profile.get("name")).isEqualTo("Asha Rao");
        assertThat(profile.get("skills")).isEqualTo(List.of("Java"));
        assertThat(profile.get("applications")).isInstanceOf(List.class);
        assertThat(profile.keySet().stream().map(Object::toString))
                .doesNotContain("_events", "mergeLock", "owner", "tenantId", "normalizedName", "_id");
        assertThat(section.toString()).doesNotContain("Ben Other");
    }

    @Test
    void erasureKeepsOnlyTheIdAndTheTombstoneFlag() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts()).containsEntry("profiles", 2).containsEntry("mergeSuggestions", 1);
        var row = mongo.one("candidates", "cand-1");
        assertThat(row.keySet()).containsExactlyInAnyOrder("_id", "tenantId", "erased", "erasedAt");
        assertThat(mongo.one("candidates", "cand-0").keySet())
                .containsExactlyInAnyOrder("_id", "tenantId", "mergedInto", "erased", "erasedAt");
        assertThat(mongo.collection("merge_suggestions"))
                .extracting(d -> d.getString("_id"))
                .containsExactly("s2");
        assertThat(mongo.collection("merge_dismissals")).isEmpty();
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(mongo.collection("candidates").stream()
                        .filter(d -> "cand-2".equals(d.getString("_id")))
                        .findFirst()
                        .orElseThrow()
                        .getString("name"))
                .isEqualTo("Ben Other");
        var otherTenant = mongo.collection("candidates").stream()
                .filter(d -> "t2".equals(d.getString("tenantId")))
                .findFirst()
                .orElseThrow();
        assertThat(otherTenant.getString("email")).isEqualTo("other-tenant@example.test");
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(data.export("t1", "cand-1").get("profiles")).isEqualTo(List.of());
    }

    @Test
    void aLateProjectionDoesNotRebuildAnErasedProfile() {
        data.erase("t1", "req-1", "cand-1");
        var service = new CandidateService(mongo.template, mock(AuditRecorder.class));

        var result = service.project(new Document("tenantId", "t1")
                .append("candidateId", "cand-1")
                .append("applicationId", "app-9")
                .append("jobTitle", "Engineer")
                .append("stage", "APPLIED")
                .append("revision", 5));

        assertThat(result.getString("id")).isEqualTo("cand-1");
        assertThat(mongo.one("candidates", "cand-1").keySet())
                .containsExactlyInAnyOrder("_id", "tenantId", "erased", "erasedAt");
    }
}
