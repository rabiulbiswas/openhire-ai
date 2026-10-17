package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.doc;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssessmentCompletedConsumerTest {
    private final FakeSubmissions submissions = new FakeSubmissions();
    private final AssessmentCompletedConsumer consumer = new AssessmentCompletedConsumer(submissions.mongo);

    @BeforeEach
    void setUp() {
        submissions.put(new Document("_id", "app-1")
                .append("tenantId", "t1")
                .append("stage", "AI_ASSESSED")
                .append("revision", 5L)
                .append("resumeId", "res-1")
                .append("score", 50)
                .append("jobSnapshot", new Document("version", 2))
                .append(
                        "assessment",
                        new Document("status", "PARTIAL")
                                .append("label", "AI unavailable, partial assessment")
                                .append("score", 50)
                                .append("resumeExcerpt", "Java and Kafka engineer")
                                .append("criteria", List.of())));
    }

    private static String event(String eventId, String status, int score, int jobVersion, String createdAt) {
        return new Document("eventId", eventId)
                .append("eventType", "assessment.completed.v1")
                .append("tenantId", "t1")
                .append(
                        "payload",
                        new Document("applicationId", "app-1")
                                .append("assessmentId", "asm-" + eventId)
                                .append("resumeId", "res-1")
                                .append("status", status)
                                .append("score", score)
                                .append("confidence", "HIGH")
                                .append("jobVersion", jobVersion)
                                .append("rubricVersion", String.valueOf(jobVersion))
                                .append(
                                        "criteria",
                                        List.of(new Document("id", "r1")
                                                .append("label", "Java")
                                                .append("requirement", "MUST")
                                                .append("weight", 30)
                                                .append("score", 90)
                                                .append("evidence", List.of("Built Kafka pipelines in Java"))
                                                .append("grounded", true)))
                                .append("strengths", List.of("Java"))
                                .append("gapsToProbe", List.of())
                                .append("hardGaps", List.of())
                                .append(
                                        "provenance",
                                        new Document("model", "qwen2.5:3b-instruct")
                                                .append("promptKey", "match.criterion.judge")
                                                .append("promptVersion", "1")
                                                .append("createdAt", createdAt))
                                .append(
                                        "label",
                                        status.equals("COMPLETE")
                                                ? "AI recommendation - requires human review"
                                                : "AI unavailable, partial assessment"))
                .toJson();
    }

    private Document assessment() {
        return doc(submissions.rows.get("app-1").get("assessment"));
    }

    @Test
    void completeReplacesTheStoredAssessmentAndScoreButNeverTheStage() {
        consumer.consume(event("e1", "COMPLETE", 82, 2, "2026-10-01T10:00:00Z"));

        var row = submissions.rows.get("app-1");
        assertThat(row.get("stage")).isEqualTo("AI_ASSESSED");
        assertThat(row.get("score")).isEqualTo(82);
        assertThat(row.get("revision")).isEqualTo(5L);
        assertThat(assessment().getString("status")).isEqualTo("COMPLETE");
        assertThat(assessment().getString("label")).isEqualTo("AI recommendation - requires human review");
        assertThat(assessment().getString("modelVersion")).isEqualTo("qwen2.5:3b-instruct / match.criterion.judge@1");
        assertThat(assessment().getString("resumeExcerpt")).isEqualTo("Java and Kafka engineer");
        var criterion = doc(((List<?>) assessment().get("criteria")).get(0));
        assertThat(criterion.get("evidence")).isEqualTo(List.of("Built Kafka pipelines in Java"));
        assertThat(criterion.getBoolean("grounded")).isTrue();
        assertThat(doc(assessment().get("provenance")).getString("model")).isEqualTo("qwen2.5:3b-instruct");
    }

    @Test
    void noAiEventCanChangeTheStageOrAnyDecisionField() {
        for (String stage : List.of("AI_ASSESSED", "SHORTLISTED", "INTERVIEWING", "REJECTED")) {
            submissions.rows.get("app-1").put("stage", stage);
            consumer.consume(event("e-" + stage, "COMPLETE", 90, 2, "2026-10-01T10:00:00Z"));
            assertThat(submissions.rows.get("app-1").get("stage")).isEqualTo(stage);
        }
        assertThat(submissions.updates).isNotEmpty();
        for (var update : submissions.updates) {
            assertThat(doc(update.get("$set")).keySet())
                    .doesNotContain("stage", "decidedBy", "lastDecision", "processingState");
            assertThat(update.keySet()).doesNotContain("$unset", "$inc");
        }
    }

    @Test
    void humanOverrideIsKeptAndShownNextToTheAiScore() {
        var override = new Document("by", "recruiter-1").append("newScore", 91).append("reason", "Strong referral");
        submissions.rows.get("app-1").put("score", 91);
        doc(submissions.rows.get("app-1").get("assessment")).put("overridden", override);

        consumer.consume(event("e1", "COMPLETE", 62, 2, "2026-10-01T10:00:00Z"));

        assertThat(assessment().get("score")).isEqualTo(62);
        assertThat(doc(assessment().get("overridden")).get("newScore")).isEqualTo(91);
        assertThat(submissions.rows.get("app-1").get("score")).isEqualTo(91);
    }

    @Test
    void partialNeverReplacesAComplete() {
        consumer.consume(event("e1", "COMPLETE", 82, 2, "2026-10-01T10:00:00Z"));
        consumer.consume(event("e2", "PARTIAL", 40, 2, "2026-10-01T11:00:00Z"));

        assertThat(assessment().getString("status")).isEqualTo("COMPLETE");
        assertThat(assessment().get("score")).isEqualTo(82);
    }

    @Test
    void partialMayReplaceTheKeywordScreeningButANewerCompleteReplacesAnOlderComplete() {
        consumer.consume(event("e1", "PARTIAL", 40, 2, "2026-10-01T09:00:00Z"));
        assertThat(assessment().getString("status")).isEqualTo("PARTIAL");
        assertThat(assessment().getString("label")).isEqualTo("AI unavailable, partial assessment");

        consumer.consume(event("e2", "COMPLETE", 70, 2, "2026-10-01T10:00:00Z"));
        consumer.consume(event("e3", "COMPLETE", 55, 2, "2026-10-01T09:30:00Z"));
        assertThat(assessment().get("score")).isEqualTo(70);
        consumer.consume(event("e4", "COMPLETE", 88, 2, "2026-10-01T12:00:00Z"));
        assertThat(assessment().get("score")).isEqualTo(88);
    }

    @Test
    void staleJobVersionAndOtherResumesAreIgnored() {
        consumer.consume(event("e1", "COMPLETE", 99, 1, "2026-10-01T10:00:00Z"));
        assertThat(assessment().getString("status")).isEqualTo("PARTIAL");

        var otherResume = Document.parse(event("e2", "COMPLETE", 99, 2, "2026-10-01T10:00:00Z"));
        doc(otherResume.get("payload")).put("resumeId", "res-old");
        consumer.consume(otherResume.toJson());
        assertThat(assessment().getString("status")).isEqualTo("PARTIAL");
    }

    @Test
    void redeliveryOfTheSameEventIdChangesNothingMore() {
        consumer.consume(event("e1", "COMPLETE", 82, 2, "2026-10-01T10:00:00Z"));
        int updates = submissions.updates.size();

        consumer.consume(event("e1", "COMPLETE", 10, 2, "2026-10-01T10:00:00Z"));

        assertThat(submissions.updates).hasSize(updates);
        assertThat(assessment().get("score")).isEqualTo(82);
        assertThat(submissions.rows.get("app-1").get("aiEventIds")).isEqualTo(List.of("e1"));
    }

    @Test
    void notEligibleOtherEventTypesAndUnknownApplicationsAreIgnored() {
        consumer.consume(event("e1", "NOT_ELIGIBLE", 0, 2, "2026-10-01T10:00:00Z"));
        consumer.consume(new Document("eventType", "assessment.other.v1")
                .append("tenantId", "t1")
                .toJson());
        var unknown = Document.parse(event("e2", "COMPLETE", 80, 2, "2026-10-01T10:00:00Z"));
        doc(unknown.get("payload")).put("applicationId", "missing");
        consumer.consume(unknown.toJson());

        assertThat(submissions.updates).isEmpty();
        assertThat(Map.of("status", assessment().getString("status"))).containsEntry("status", "PARTIAL");
    }

    @Test
    void currentIntegrityFlagsSurviveTheReplacement() {
        submissions
                .rows
                .get("app-1")
                .put(
                        "integrityFlags",
                        List.of(new Document("id", "sig-1")
                                .append("type", "TIMELINE_OVERLAP")
                                .append("severity", "HIGH")
                                .append("reason", "Roles overlap")));

        consumer.consume(event("e1", "COMPLETE", 82, 2, "2026-10-01T10:00:00Z"));

        assertThat((List<?>) assessment().get("integrityFlags")).hasSize(1);
        assertThat(doc(assessment().get("integrityFlag")).getString("reason")).isEqualTo("Roles overlap");
    }
}
