package com.openhire.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DecisionHistoryTest {
    private final Rig rig = new Rig();

    private Map<String, Object> assessment() {
        return Maps.of(
                "applicationId", "app_1",
                "candidateId", "cand_1",
                "assessmentId", "asm_1",
                "status", "COMPLETE",
                "score", 72,
                "confidence", "HIGH",
                "jobId", "job_1",
                "jobVersion", 1,
                "rubricVersion", "rubric-v1",
                "criteria",
                        List.of(Maps.of(
                                "id",
                                "c1",
                                "label",
                                "Kafka",
                                "requirement",
                                "MUST",
                                "weight",
                                3,
                                "score",
                                80,
                                "evidence",
                                List.of("Built an order pipeline on Kafka"),
                                "grounded",
                                true)),
                "hardGaps", List.of(),
                "provenance",
                        Maps.of(
                                "model", "llama3.2",
                                "promptKey", "match.criterion.judge",
                                "promptVersion", "1",
                                "createdAt", "2026-10-01T09:00:00Z",
                                "traceId", "tr1"));
    }

    @BeforeEach
    void events() {
        rig.directory.add("app_1", "cand_1", "Asha Rao", "job_1", "SHORTLISTED");
        rig.submitted("app_1", "job_1", "2026-10-01T08:00:00Z");
        rig.projections.accept(
                Rig.envelope("assessment.completed.v1", "t1", "asm_1", "2026-10-01T09:00:01Z", assessment()));
        rig.projections.accept(Rig.envelope(
                "integrity.signal.raised.v1",
                "t1",
                "sig_1",
                "2026-10-01T09:05:00Z",
                Maps.of(
                        "applicationId",
                        "app_1",
                        "candidateId",
                        "cand_1",
                        "signalId",
                        "sig_1",
                        "type",
                        "TIMELINE_OVERLAP",
                        "severity",
                        "LOW")));
        rig.stage("app_1", "AI_ASSESSED", "SHORTLISTED", "rec_1", "2026-10-02T10:00:00Z");
        rig.projections.accept(Rig.envelope(
                "offer.created.v1",
                "t1",
                "off_1",
                "2026-10-05T10:00:00Z",
                Maps.of(
                        "offerId",
                        "off_1",
                        "applicationId",
                        "app_1",
                        "candidateId",
                        "cand_1",
                        "status",
                        "DRAFT",
                        "candidateName",
                        "Asha Rao")));
        rig.ledger.ingest(Rig.auditEvent("t1", "APPLICATION_SHORTLISTED", "app_1", "cand_1", "2026-10-02T10:00:00Z"));
    }

    @Test
    void anAssessmentBecomesADecisionWithItsEvidenceAndNoCandidateNameStored() {
        var list = rig.history.list("t1", null, "app_1", null, null, 10);

        assertThat(list).hasSize(1);
        var decision = list.getFirst();
        assertThat(decision)
                .containsEntry("id", "asm_1")
                .containsEntry("candidateName", "Asha Rao")
                .containsEntry("task", "MATCH_ASSESSMENT")
                .containsEntry("model", "llama3.2")
                .containsEntry("promptVersion", "1")
                .containsEntry("rubricVersion", "rubric-v1");
        assertThat(Maps.map(decision.get("outcome")))
                .containsEntry("status", "COMPLETE")
                .containsEntry("score", 72)
                .containsEntry("confidence", "HIGH");
        assertThat(Maps.map(decision.get("humanAction")))
                .containsEntry("action", "SHORTLISTED")
                .containsEntry("by", "rec_1");
        assertThat(rig.decisions.rows.values().toString()).doesNotContain("Asha");
        assertThat(rig.activity.docs.values().toString()).doesNotContain("Asha");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDetailHasCriteriaQuotesIntegrityFlagsAndHumanActions() {
        var detail = rig.history.get("t1", "asm_1");

        var criterion = Maps.list(detail.get("criteria")).getFirst();
        assertThat(criterion).containsEntry("grounded", true);
        assertThat((List<Object>) criterion.get("evidence")).containsExactly("Built an order pipeline on Kafka");
        assertThat(Maps.list(detail.get("integrityFlags"))).singleElement().satisfies(f -> assertThat(f)
                .containsEntry("type", "TIMELINE_OVERLAP")
                .containsEntry("status", "RAISED"));
        assertThat(Maps.list(detail.get("humanActions"))).hasSize(1);
    }

    @Test
    void aNameThatCannotBeResolvedIsLeftOutRatherThanFailingTheList() {
        rig.directory.down = true;
        assertThat(rig.history.list("t1", null, null, null, null, 10).getFirst())
                .doesNotContainKey("candidateName");
    }

    @Test
    void anotherTenantSeesNothingAndGetsNotFound() {
        assertThat(rig.history.list("t2", null, null, null, null, 10)).isEmpty();
        assertThatThrownBy(() -> rig.history.get("t2", "asm_1")).hasMessageContaining("not found");
        assertThat(rig.history.trace("t2", "app_1")).isEmpty();
    }

    @Test
    void theTraceJoinsAuditAiStageAndOfferInOrder() {
        var trace = rig.history.trace("t1", "app_1");

        assertThat(trace).extracting(e -> e.get("kind")).contains("AUDIT", "AI", "STAGE", "OFFER");
        assertThat(trace).extracting(e -> (String) e.get("at")).isSorted();
        var ai = trace.stream()
                .filter(e -> "AI".equals(e.get("kind"))
                        && String.valueOf(e.get("ref")).startsWith("ai-decision"))
                .findFirst()
                .orElseThrow();
        assertThat((String) ai.get("summary"))
                .contains("llama3.2")
                .contains("rubric-v1")
                .contains("score 72");
        assertThat(trace).anySatisfy(e -> assertThat((String) e.get("summary"))
                .contains("Stage changed from AI_ASSESSED to SHORTLISTED"));
    }

    @Test
    void replayedEventsDoNotDuplicateTheTimeline() {
        var event = Rig.envelope("ApplicationScreened", "t1", "app_1", "2026-10-01T08:30:00Z", Maps.of("score", 40));
        rig.projections.accept(event);
        rig.projections.accept(event);
        var timeline = Maps.list(rig.activity.find("t1", "app_1").orElseThrow().get("timeline"));
        assertThat(timeline.stream()
                        .filter(t -> ((String) t.get("summary")).startsWith("Rule-based"))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void undoingADecisionRestoresTheEarlierStage() {
        rig.stage("app_1", "SHORTLISTED", "REJECTED", "rec_1", "2026-10-06T10:00:00Z");
        assertThat(rig.activity.find("t1", "app_1").orElseThrow()).containsEntry("lifecycle", "REJECTED");
        rig.projections.accept(Rig.envelope(
                "ApplicationDecisionUndone", "t1", "app_1", "2026-10-06T10:05:00Z", Maps.of("actorId", "rec_1")));
        assertThat(rig.activity.find("t1", "app_1").orElseThrow())
                .containsEntry("stage", "SHORTLISTED")
                .containsEntry("lifecycle", "ACTIVE");
    }

    @Test
    void eventsForErasedCandidatesAreSkipped() {
        rig.erased.mark("t1", "cand_9", "r1");
        rig.projections.accept(Rig.envelope(
                "assessment.completed.v1",
                "t1",
                "asm_9",
                "2026-10-01T09:00:01Z",
                Maps.of(
                        "applicationId",
                        "app_9",
                        "candidateId",
                        "cand_9",
                        "assessmentId",
                        "asm_9",
                        "status",
                        "COMPLETE",
                        "score",
                        50)));
        assertThat(rig.activity.find("t1", "app_9")).isEmpty();
        assertThat(rig.decisions.find("t1", "asm_9")).isEmpty();
    }
}
