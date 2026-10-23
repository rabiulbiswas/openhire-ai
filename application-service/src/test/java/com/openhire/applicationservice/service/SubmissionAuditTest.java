package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.service.port.ConsentSource;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SubmissionAuditTest {
    private final FakeSubmissions store = new FakeSubmissions();
    private final List<AuditRecord> records = new ArrayList<>();
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final SubmissionService service;

    SubmissionAuditTest() {
        doAnswer(call -> records.add(call.getArgument(0))).when(audit).record(any());
        service = build(audit);
    }

    private SubmissionService build(AuditRecorder recorder) {
        ConsentSource none = tenant -> Optional.empty();
        return new SubmissionService(
                store.mongo,
                new Access("local", "t1", "secret"),
                new ObjectMapper(),
                "http://127.0.0.1:1",
                "http://127.0.0.1:1",
                "http://127.0.0.1:1",
                new ConsentTexts(none, "privacy-v1", "t1"),
                recorder);
    }

    private Document application(String id, String stage) {
        return store.put(new Document("_id", id)
                .append("tenantId", "t1")
                .append("candidateId", "cand-1")
                .append("name", "Ada Lovelace")
                .append("email", "ada@example.test")
                .append("stage", stage)
                .append("revision", 0L)
                .append("processingState", "COMPLETE"));
    }

    private AuditRecord only() {
        assertThat(records).hasSize(1);
        var r = records.getFirst();
        assertThat(r.resourceType()).isEqualTo("Application");
        assertThat(r.resourceId()).isNotBlank();
        assertThat(r.tenantId()).isEqualTo("t1");
        assertThat(r.toString()).doesNotContain("Ada", "example.test");
        return r;
    }

    @Test
    void shortlistIsAuditedWithActorAndCandidate() {
        application("a1", "AI_ASSESSED");
        service.move("t1", "a1", "user-1", "SHORTLISTED", "Reviewed by recruiter", null);
        var r = only();
        assertThat(r.action()).isEqualTo("APPLICATION_SHORTLISTED");
        assertThat(r.actorType()).isEqualTo(AuditRecord.ActorType.USER);
        assertThat(r.actorId()).isEqualTo("user-1");
        assertThat(r.candidateId()).isEqualTo("cand-1");
        assertThat(r.applicationId()).isEqualTo("a1");
    }

    @Test
    void rejectionCarriesReasonCodeNotTheFreeTextReason() {
        application("a1", "SHORTLISTED");
        service.move("t1", "a1", "user-1", "REJECTED", "Not enough Kubernetes for Ada", null, "SKILLS_GAP");
        var r = only();
        assertThat(r.action()).isEqualTo("APPLICATION_REJECTED");
        assertThat(r.details()).containsOnlyKeys("reasonCode").containsEntry("reasonCode", "SKILLS_GAP");
    }

    @Test
    void humanStageChangeRecordsFromAndTo() {
        application("a1", "SHORTLISTED");
        service.move("t1", "a1", "user-1", "INTERVIEWING", "Stage changed by recruiter", null);
        var r = only();
        assertThat(r.action()).isEqualTo("APPLICATION_STAGE_CHANGED");
        assertThat(r.details()).containsEntry("from", "SHORTLISTED").containsEntry("to", "INTERVIEWING");
    }

    @Test
    void failedMoveIsNotAudited() {
        application("a1", "AI_ASSESSED");
        assertThatThrownBy(() -> service.move("t1", "a1", "user-1", "OFFER", "x", null))
                .isInstanceOf(Exception.class);
        assertThat(records).isEmpty();
    }

    @Test
    void withdrawalIsACandidateAction() {
        application("a1", "SHORTLISTED");
        service.withdraw("t1", "a1", "career:abc", "Withdrawn by applicant");
        var r = only();
        assertThat(r.action()).isEqualTo("APPLICATION_WITHDRAWN");
        assertThat(r.actorType()).isEqualTo(AuditRecord.ActorType.CANDIDATE);
        assertThat(r.actorId()).isEqualTo("cand-1");
        assertThat(store.rows.get("a1").getString("stage")).isEqualTo("WITHDRAWN");
    }

    @Test
    void undoIsAudited() {
        application("a1", "AI_ASSESSED");
        service.move("t1", "a1", "user-1", "SHORTLISTED", "Reviewed by recruiter", null);
        records.clear();
        service.undo("t1", "a1", "user-1");
        var r = only();
        assertThat(r.action()).isEqualTo("DECISION_UNDONE");
        assertThat(r.details()).containsEntry("from", "SHORTLISTED").containsEntry("to", "AI_ASSESSED");
    }

    @Test
    void hireDecisionRecordsOnlyTheDecision() {
        application("a1", "INTERVIEWING");
        service.decide("t1", "a1", "user-1", "Grace Hopper", "HIRE", "Strong loop, great references");
        var r = only();
        assertThat(r.action()).isEqualTo("HIRE_DECISION_RECORDED");
        assertThat(r.actorName()).isEqualTo("Grace Hopper");
        assertThat(r.details()).containsOnlyKeys("decision").containsEntry("decision", "HIRE");
    }

    @Test
    void eligibilityReviewIsAudited() {
        application("a1", "NEEDS_ATTENTION").append("attentionCode", "KNOCKOUT_REVIEW");
        service.reviewEligibility("t1", "a1", "user-1", "Visa confirmed by phone");
        var r = only();
        assertThat(r.action()).isEqualTo("ELIGIBILITY_REVIEWED");
        assertThat(r.actorId()).isEqualTo("user-1");
        assertThat(store.rows.get("a1").getString("stage")).isEqualTo("APPLIED");
    }

    @Test
    void overrideStoresReasonCodeAndAuditsScores() {
        application("a1", "AI_ASSESSED")
                .append("assessment", new Document("score", 40))
                .append("score", 40);
        service.overrideScore(
                "t1", "a1", "user-1", 75, "Led a similar migration at a previous job", "CONTEXT_NOT_IN_RESUME");
        var r = only();
        assertThat(r.action()).isEqualTo("ASSESSMENT_OVERRIDDEN");
        assertThat(r.details())
                .containsOnlyKeys("reasonCode", "newScore", "previousScore")
                .containsEntry("reasonCode", "CONTEXT_NOT_IN_RESUME")
                .containsEntry("newScore", 75)
                .containsEntry("previousScore", 40);
        var update = store.updates.getLast();
        assertThat(((Document) update.get("$set")).get("assessment.overridden").toString())
                .contains("reasonCode=CONTEXT_NOT_IN_RESUME");
        assertThat(update.get("$push").toString()).contains("reasonCode=CONTEXT_NOT_IN_RESUME");
    }

    @Test
    void recorderFailureNeverBreaksTheAction() {
        var failing = new AuditRecorder(
                new AuditRecorder.Outbox() {
                    public void add(String eventId, String tenantId, String json) {
                        throw new IllegalStateException("outbox down");
                    }

                    public List<Pending> pending(int limit) {
                        return List.of();
                    }

                    public void remove(String eventId) {}
                },
                (key, json) -> {},
                "application-service",
                true);
        application("a1", "AI_ASSESSED");
        var moved = build(failing).move("t1", "a1", "user-1", "SHORTLISTED", "Reviewed by recruiter", null);
        assertThat(moved.getString("stage")).isEqualTo("SHORTLISTED");
    }

    @Test
    void importCompletionCarriesCountsOnly() {
        service.importCompleted(
                "t1",
                "imp-1",
                "user-1",
                new Document("total", 10)
                        .append("imported", 8)
                        .append("skippedNoConsent", 1)
                        .append("failed", 1L));
        var r = records.getFirst();
        assertThat(r.action()).isEqualTo("IMPORT_COMPLETED");
        assertThat(r.resourceType()).isEqualTo("Import");
        assertThat(r.details()).containsEntry("total", 10L).containsEntry("failed", 1L);
    }
}
