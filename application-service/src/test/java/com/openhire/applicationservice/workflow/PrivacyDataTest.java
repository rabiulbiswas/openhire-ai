package com.openhire.applicationservice.workflow;

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

    private static Document submission(String id, String tenant, String candidate, String name) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("candidateId", candidate)
                .append("jobId", "job-1")
                .append("jobTitle", "Engineer")
                .append("source", "CAREER")
                .append("stage", "SHORTLISTED")
                .append("name", name)
                .append("email", name.toLowerCase().replace(' ', '.') + "@example.test")
                .append("phone", "+91 90000 00000")
                .append("answers", new Document("q1", "Yes, 5 years"))
                .append("fingerprint", "f1")
                .append("receiptHash", "hash")
                .append("revision", 3L)
                .append("score", 82)
                .append("processingState", "PENDING")
                .append("resumeName", name + " CV.pdf")
                .append("matchingCommands", List.of(new Document("eventId", "e1")))
                .append(
                        "integrityFlags",
                        List.of(new Document("id", "s1").append("type", "T").append("reason", "Overlap of roles")))
                .append(
                        "assessment",
                        new Document("score", 82)
                                .append("resumeExcerpt", "private resume text")
                                .append("strengths", List.of("Led a team"))
                                .append(
                                        "criteria",
                                        List.of(new Document("id", "c1")
                                                .append("score", 90)
                                                .append("evidence", List.of("exact quote"))))
                                .append(
                                        "overridden",
                                        new Document("by", "u1")
                                                .append("newScore", 85)
                                                .append("reason", "context")))
                .append(
                        "hireDecision",
                        new Document("decision", "HIRE")
                                .append("reason", "great fit")
                                .append("decidedBy", "u1"))
                .append(
                        "decisions",
                        List.of(new Document("from", "APPLIED")
                                .append("to", "SHORTLISTED")
                                .append("reason", "looks good")));
    }

    @BeforeEach
    void rows() {
        mongo.put("submissions", submission("app-1", "t1", "cand-1", "Asha Rao"));
        mongo.put("submissions", submission("app-2", "t1", "cand-1", "Asha Rao"));
        mongo.put("submissions", submission("app-3", "t1", "cand-2", "Ben Other"));
        mongo.put("submissions", submission("app-4", "t2", "cand-1", "Asha Rao"));
        mongo.put(
                "import_rows",
                new Document("_id", "r1")
                        .append("tenantId", "t1")
                        .append("applicationId", "app-1")
                        .append(
                                "input",
                                new Document("name", "Asha Rao")
                                        .append("email", "a@example.test")
                                        .append("jobId", "job-1"))
                        .append("file", new byte[] {1}));
        mongo.put(
                "import_rows",
                new Document("_id", "r2")
                        .append("tenantId", "t1")
                        .append("applicationId", "app-3")
                        .append("input", new Document("name", "Ben Other").append("email", "b@example.test")));
    }

    @Test
    void exportListsTheCandidatesApplicationsWithoutSecretsOrOtherPeople() {
        var section = data.export("t1", "cand-1");

        var applications = (List<?>) section.get("applications");
        assertThat(applications).hasSize(2);
        var first = (Map<?, ?>) applications.getFirst();
        assertThat(first.get("email")).isEqualTo("asha.rao@example.test");
        assertThat(first.get("answers")).isEqualTo(Map.of("q1", "Yes, 5 years"));
        assertThat(first.keySet().stream().map(Object::toString))
                .doesNotContain("fingerprint", "receiptHash", "actorId", "owner", "matchingCommands");
        assertThat(section.toString()).doesNotContain("Ben Other").doesNotContain("hash");
    }

    @Test
    void erasureAnonymisesButKeepsIdsStagesAndScores() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts()).containsEntry("applications", 2).containsEntry("importRows", 1);
        var row = mongo.one("submissions", "app-1");
        assertThat(row.getString("name")).isEqualTo("Erased candidate");
        assertThat(row).doesNotContainKeys("email", "phone", "resumeName", "matchingCommands");
        assertThat((Document) row.get("answers")).isEmpty();
        assertThat(row.getString("stage")).isEqualTo("SHORTLISTED");
        assertThat(row.getString("jobId")).isEqualTo("job-1");
        assertThat(row.getInteger("score")).isEqualTo(82);
        assertThat(row.getString("candidateId")).isEqualTo("cand-1");
        assertThat(row.getString("processingState")).isEqualTo("COMPLETE");
        var assessment = (Document) row.get("assessment");
        assertThat(assessment.getInteger("score")).isEqualTo(82);
        assertThat(assessment).doesNotContainKey("resumeExcerpt");
        assertThat(assessment.getList("strengths", String.class)).isEmpty();
        var criterion =
                (Document) assessment.getList("criteria", Document.class).getFirst();
        assertThat(criterion.getList("evidence", String.class)).isEmpty();
        assertThat(criterion.getInteger("score")).isEqualTo(90);
        assertThat(((Document) assessment.get("overridden")).getInteger("newScore"))
                .isEqualTo(85);
        assertThat((Document) assessment.get("overridden")).doesNotContainKey("reason");
        assertThat((Document) row.get("hireDecision")).doesNotContainKey("reason");
        assertThat(((Document) row.getList("decisions", Document.class).getFirst()).getString("reason"))
                .isEmpty();
        assertThat(row.getList("integrityFlags", Document.class).getFirst().getString("reason"))
                .isEmpty();
        var staged = mongo.one("import_rows", "r1");
        assertThat((Document) staged.get("input"))
                .doesNotContainKeys("name", "email")
                .containsKey("jobId");
        assertThat(staged).doesNotContainKey("file");
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(mongo.one("submissions", "app-3").getString("name")).isEqualTo("Ben Other");
        assertThat(mongo.one("submissions", "app-3").getString("email")).isNotNull();
        assertThat(mongo.one("submissions", "app-4").getString("name")).isEqualTo("Asha Rao");
        assertThat(mongo.one("import_rows", "r2").get("input", Document.class).getString("email"))
                .isEqualTo("b@example.test");
        assertThat(tombstones.isErased("t2", "cand-1")).isFalse();
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(again.counts()).containsEntry("applications", 0).containsEntry("importRows", 0);
        assertThat(data.export("t1", "cand-1").get("applications")).isEqualTo(List.of());
    }

    @Test
    void applicationIdsStayResolvableAfterErasure() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(data.applicationIds("t1", "cand-1")).containsExactly("app-1", "app-2");
        assertThat(data.applicationIds("t1", "nobody")).isEmpty();
    }

    @Test
    void lateAssessmentAndIntegrityEventsSkipAnErasedApplication() {
        data.erase("t1", "req-1", "cand-1");
        var before = mongo.one("submissions", "app-1");
        var submissions = new FakeSubmissions();
        submissions.put(InMemoryMongo.copy(before));

        new AssessmentCompletedConsumer(submissions.mongo)
                .consume(new Document("eventType", "assessment.completed.v1")
                        .append("eventId", "ev1")
                        .append("tenantId", "t1")
                        .append(
                                "payload",
                                new Document("applicationId", "app-1")
                                        .append("status", "COMPLETE")
                                        .append("score", 10))
                        .toJson());
        new IntegritySignalConsumer(submissions.mongo)
                .consume(new Document("eventType", "integrity.signal.raised.v1")
                        .append("tenantId", "t1")
                        .append(
                                "payload",
                                new Document("applicationId", "app-1")
                                        .append("signalId", "s9")
                                        .append("type", "T")
                                        .append("evidence", "Resurrected text"))
                        .toJson());

        var row = submissions.rows.get("app-1");
        assertThat(((Document) row.get("assessment")).getInteger("score")).isEqualTo(82);
        assertThat(row.getList("integrityFlags", Document.class)).hasSize(1);
        assertThat(submissions.updates).isEmpty();
    }
}
