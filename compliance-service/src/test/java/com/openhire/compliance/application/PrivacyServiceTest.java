package com.openhire.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyServiceTest {
    private final Rig rig = new Rig();
    private final Actor officer = new Actor("u1", "Dev");

    @BeforeEach
    void candidate() {
        rig.directory.add("app_1", "cand_1", "Asha Rao", "job_1", "REJECTED");
        rig.submitted("app_1", "job_1", "2026-01-01T08:00:00Z");
    }

    private Map<String, Object> ack(String requestId, String service, String status) {
        return Rig.envelope(
                "privacy.erasure.completed.v1",
                "t1",
                requestId,
                "2026-10-15T10:01:00Z",
                Maps.of(
                        "requestId",
                        requestId,
                        "service",
                        service,
                        "candidateId",
                        "cand_1",
                        "status",
                        status,
                        "note",
                        "RETAINED_LEGAL_BASIS".equals(status) ? "Employment record retention obligation" : null,
                        "counts",
                        Map.of("rows", 2)));
    }

    private Map<String, Object> exportAck(String requestId, String service, Map<String, Object> section) {
        return Rig.envelope(
                "privacy.export.completed.v1",
                "t1",
                requestId,
                "2026-10-15T10:01:00Z",
                Maps.of(
                        "requestId",
                        requestId,
                        "service",
                        service,
                        "candidateId",
                        "cand_1",
                        "status",
                        "EXPORTED",
                        "section",
                        section));
    }

    private String erasure() {
        return (String) rig.privacy
                .createByStaff("t1", officer, "cand_1", "ERASURE", "Candidate asked")
                .get("id");
    }

    @Test
    void creatingAnErasureEmitsTheRequestAndAuditsIt() {
        var request = rig.privacy.createByStaff("t1", officer, "cand_1", "ERASURE", "Candidate asked");

        assertThat(request).containsEntry("status", "IN_PROGRESS").containsEntry("source", "STAFF");
        assertThat(Maps.map(request.get("participants")).keySet())
                .containsExactly("application-service", "talent-service", "employee-service");
        var event = rig.events.of("compliance.erasure.requested.v1").getFirst();
        assertThat(event.payload())
                .containsEntry("requestId", request.get("id"))
                .containsEntry("candidateId", "cand_1")
                .containsEntry("type", "ERASURE")
                .containsEntry("source", "STAFF")
                .containsEntry("reason", "Candidate asked");
        assertThat(rig.sink.of("PRIVACY_REQUEST_CREATED")).hasSize(1);
        assertThat((String) request.get("dueAt")).startsWith("2026-11-14");
    }

    @Test
    void completesOnlyWhenEveryParticipantHasAnsweredAndDuplicatesAreIgnored() {
        var id = erasure();

        assertThat(rig.privacy.onAck(ack(id, "application-service", "ERASED"))).isTrue();
        assertThat(rig.privacy.onAck(ack(id, "application-service", "ERASED"))).isFalse();
        assertThat(rig.privacy.get("t1", id)).containsEntry("status", "IN_PROGRESS");
        rig.privacy.onAck(ack(id, "talent-service", "NOTHING_HELD"));
        assertThat(rig.privacy.get("t1", id)).containsEntry("status", "IN_PROGRESS");
        rig.privacy.onAck(ack(id, "employee-service", "RETAINED_LEGAL_BASIS"));

        var done = rig.privacy.get("t1", id);
        assertThat(done).containsEntry("status", "COMPLETED");
        assertThat(rig.events.of("compliance.erasure.completed.v1")).hasSize(1);
        assertThat(rig.sink.of("PRIVACY_REQUEST_COMPLETED")).hasSize(1);
    }

    @Test
    void ignoresAcksForUnknownRequestsServicesAndTypes() {
        var id = erasure();
        assertThat(rig.privacy.onAck(ack("prv_missing", "application-service", "ERASED")))
                .isFalse();
        assertThat(rig.privacy.onAck(ack(id, "mystery-service", "ERASED"))).isFalse();
        assertThat(rig.privacy.onAck(exportAck(id, "application-service", Map.of())))
                .isFalse();
    }

    @Test
    void invalidAcknowledgementsCannotCompleteOrPoisonARequest() {
        var id = erasure();
        for (var status : List.of("FAILED", "UNKNOWN", "EXPORTED", "PENDING"))
            assertThat(rig.privacy.onAck(ack(id, "application-service", status)))
                    .isFalse();
        var wrongCandidate = ack(id, "application-service", "ERASED");
        var payload = Maps.copy(Maps.map(wrongCandidate.get("payload")));
        payload.put("candidateId", "someone-else");
        wrongCandidate.put("payload", payload);
        assertThat(rig.privacy.onAck(wrongCandidate)).isFalse();
        var noBasis = ack(id, "employee-service", "RETAINED_LEGAL_BASIS");
        payload = Maps.copy(Maps.map(noBasis.get("payload")));
        payload.remove("note");
        noBasis.put("payload", payload);
        assertThat(rig.privacy.onAck(noBasis)).isFalse();
        assertThat(rig.privacy.get("t1", id)).containsEntry("status", "IN_PROGRESS");
        assertThat(rig.privacy.onAck(ack(id, "application-service", "ERASED"))).isTrue();
    }

    @Test
    void exportAcknowledgementMustContainASection() {
        var id = (String) rig.privacy
                .createByStaff("t1", officer, "cand_1", "EXPORT", null)
                .get("id");
        var event = exportAck(id, "application-service", Map.of());
        var payload = Maps.copy(Maps.map(event.get("payload")));
        payload.remove("section");
        event.put("payload", payload);
        assertThat(rig.privacy.onAck(event)).isFalse();
        assertThat(rig.privacy.onAck(exportAck(id, "application-service", Map.of())))
                .isTrue();
    }

    @Test
    void theEvidenceReportIsHashedAndTheCandidateIsReducedToATombstone() {
        rig.projections.accept(Rig.envelope(
                "assessment.completed.v1",
                "t1",
                "asm_1",
                "2026-01-02T09:00:00Z",
                Maps.of(
                        "applicationId",
                        "app_1",
                        "candidateId",
                        "cand_1",
                        "assessmentId",
                        "asm_1",
                        "status",
                        "COMPLETE",
                        "score",
                        50)));
        var id = erasure();
        for (var service : List.of("application-service", "talent-service", "employee-service"))
            rig.privacy.onAck(ack(id, service, service.startsWith("employee") ? "RETAINED_LEGAL_BASIS" : "ERASED"));

        var done = rig.privacy.get("t1", id);
        var evidence = Maps.map(done.get("evidence"));
        assertThat(Maps.list(evidence.get("perService"))).hasSize(3);
        assertThat((String) evidence.get("sha256")).hasSize(64).isEqualTo(done.get("evidenceHash"));
        assertThat(evidence).containsKey("candidateIdHash");
        assertThat(done).doesNotContainKey("candidateId").containsKey("candidateIdHash");
        assertThat(done.get("requestedBy")).isNotEqualTo("cand_1");
        var copy = Maps.copy(evidence);
        copy.remove("sha256");
        assertThat(com.openhire.compliance.domain.CanonicalJson.sha256(
                        com.openhire.compliance.domain.CanonicalJson.write(copy)))
                .isEqualTo(evidence.get("sha256"));
        assertThat(rig.activity.byCandidate("t1", "cand_1")).isEmpty();
        assertThat(rig.decisions.list("t1", "cand_1", null, null, null, 10)).isEmpty();
        assertThat(rig.erased.isErased("t1", "cand_1")).isTrue();
        assertThat(rig.erased.isErased("t1", "app:app_1")).isTrue();
        // a late event cannot resurrect the application
        rig.stage("app_1", "REJECTED", "REJECTED", "rec", "2026-10-16T10:00:00Z");
        assertThat(rig.activity.find("t1", "app_1")).isEmpty();
    }

    @Test
    void aRequestWithAMissingAnswerIsFlaggedStuckAfterTheLimit() {
        var id = erasure();
        rig.privacy.onAck(ack(id, "application-service", "ERASED"));

        assertThat(rig.privacy.flagStuck()).isZero();
        rig.clock.advance(Duration.ofHours(25));
        assertThat(rig.privacy.flagStuck()).isEqualTo(1);
        assertThat(rig.privacy.get("t1", id)).containsEntry("stuck", true).containsEntry("status", "IN_PROGRESS");
        assertThat(rig.privacy.stuck("t1")).isEqualTo(1);
        assertThat(rig.privacy.flagStuck()).isZero();
        assertThat(rig.privacy.stuck("t2")).isZero();
    }

    @Test
    void aLegalHoldBlocksErasureAndReleaseResumesIt() {
        var hold = rig.holds.place("t1", officer, null, "job_1", "Litigation", null);
        var request = rig.privacy.createByStaff("t1", officer, "cand_1", "ERASURE", null);

        assertThat(request).containsEntry("status", "BLOCKED_BY_LEGAL_HOLD");
        assertThat(rig.events.of("compliance.erasure.requested.v1")).isEmpty();
        assertThat(rig.privacy.resumeBlocked(null)).isZero();

        rig.holds.release("t1", officer, (String) hold.get("id"), "Case closed");

        assertThat(rig.privacy.get("t1", (String) request.get("id"))).containsEntry("status", "IN_PROGRESS");
        assertThat(rig.events.of("compliance.erasure.requested.v1")).hasSize(1);
        assertThat(rig.sink.of("LEGAL_HOLD_PLACED")).hasSize(1);
        assertThat(rig.sink.of("LEGAL_HOLD_RELEASED")).hasSize(1);
        assertThat(rig.events.of("compliance.legal-hold.released.v1")).hasSize(1);
    }

    @Test
    void anExpiredHoldNoLongerBlocksAndTheSweepResumes() {
        rig.holds.place("t1", officer, "cand_1", null, "Dispute", "2026-10-16T10:00:00Z");
        var request = rig.privacy.createByStaff("t1", officer, "cand_1", "ERASURE", null);
        assertThat(request).containsEntry("status", "BLOCKED_BY_LEGAL_HOLD");

        rig.clock.advance(Duration.ofDays(2));
        rig.privacy.sweep();

        assertThat(rig.privacy.get("t1", (String) request.get("id"))).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    void exportsAreNotBlockedByHoldsAndAreAssembledWithoutSecrets() {
        rig.holds.place("t1", officer, "cand_1", null, "Dispute", null);
        var request = rig.privacy.createByStaff("t1", officer, "cand_1", "EXPORT", null);
        var id = (String) request.get("id");
        assertThat(request).containsEntry("status", "IN_PROGRESS");
        assertThat(rig.events.of("compliance.export.requested.v1")).hasSize(1);

        rig.privacy.onAck(exportAck(
                id,
                "application-service",
                Maps.of(
                        "name",
                        "Asha",
                        "receiptHash",
                        "abc",
                        "nested",
                        Maps.of("apiToken", "t", "stage", "REJECTED"))));
        rig.privacy.onAck(exportAck(id, "talent-service", Map.of("profile", "none")));
        rig.privacy.onAck(exportAck(id, "employee-service", Map.of()));

        assertThat(rig.privacy.get("t1", id)).containsEntry("status", "COMPLETED");
        var export = rig.privacyStore.export("t1", id).orElseThrow();
        var sections = Maps.map(export.get("sections"));
        assertThat(sections.keySet())
                .contains("application-service", "talent-service", "employee-service", "compliance-service");
        assertThat(export.toString()).doesNotContain("receiptHash").doesNotContain("apiToken");
        assertThat(Maps.map(Maps.map(sections.get("application-service")).get("nested")))
                .containsEntry("stage", "REJECTED");
        assertThat(rig.events.of("compliance.export.ready.v1")).hasSize(1);
    }

    @Test
    void theCandidateDownloadsTheirExportUntilItExpires() {
        var created = rig.privacy.createByCandidate("t1", "app_1", "receipt-app_1", "EXPORT");
        var id = (String) created.get("id");
        assertThat(created).doesNotContainKey("participants").containsEntry("exportAvailable", false);
        assertThatThrownBy(() -> rig.privacy.candidateExport("t1", id, "receipt-app_1"))
                .isInstanceOf(ComplianceExceptions.Conflict.class);
        for (var service : List.of("application-service", "talent-service", "employee-service"))
            rig.privacy.onAck(exportAck(id, service, Map.of("ok", true)));

        assertThat(rig.privacy.candidateExport("t1", id, "receipt-app_1")).containsKey("sections");
        assertThat(rig.privacy.candidateRequests("t1", "app_1", "receipt-app_1"))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("exportAvailable", true));
        assertThatThrownBy(() -> rig.privacy.candidateExport("t1", id, "wrong"))
                .isInstanceOf(ComplianceExceptions.Forbidden.class);

        rig.clock.advance(Duration.ofDays(8));
        assertThatThrownBy(() -> rig.privacy.candidateExport("t1", id, "receipt-app_1"))
                .isInstanceOf(ComplianceExceptions.Gone.class);
    }

    @Test
    void aSecondOpenRequestIsAConflictForStaffAndReturnedToTheCandidate() {
        erasure();
        assertThatThrownBy(() -> rig.privacy.createByStaff("t1", officer, "cand_1", "ERASURE", null))
                .isInstanceOf(ComplianceExceptions.Conflict.class);
        var first = rig.privacy.createByCandidate("t1", "app_1", "receipt-app_1", "ERASURE");
        var second = rig.privacy.createByCandidate("t1", "app_1", "receipt-app_1", "ERASURE");
        assertThat(second.get("id")).isEqualTo(first.get("id"));
    }

    @Test
    void requestsOfOtherTenantsAreInvisible() {
        var id = erasure();
        assertThat(rig.privacy.list("t2", null)).isEmpty();
        assertThatThrownBy(() -> rig.privacy.get("t2", id)).hasMessageContaining("not found");
        assertThat(rig.privacy.list("t1", "IN_PROGRESS")).hasSize(1);
    }

    @Test
    void noRegisteredParticipantsCompletesImmediately() {
        var bare = new Rig(List.of());
        var request = bare.privacy.createByStaff("t1", officer, "cand_1", "ERASURE", null);
        assertThat(bare.privacy.get("t1", (String) request.get("id"))).containsEntry("status", "COMPLETED");
    }
}
