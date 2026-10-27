package com.openhire.approval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openhire.approval.domain.ApprovalRequest;
import com.openhire.approval.service.port.ApprovalEventPublisher;
import com.openhire.approval.service.port.ApprovalRepository;
import com.openhire.approval.service.port.DelegationResolver;
import com.openhire.approval.service.port.JobWorkflowClient;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalApplicationServiceTest {
    private final ApprovalRepository approvals = mock(ApprovalRepository.class);
    private final ApprovalEventPublisher events = mock(ApprovalEventPublisher.class);
    private final JobWorkflowClient jobs = mock(JobWorkflowClient.class);
    private final DelegationResolver delegations = (tenant, owner, at) -> owner;
    private final List<String> audited = new ArrayList<>();
    private boolean auditBroken;
    private final AuditRecorder audit = new AuditRecorder(
            new AuditRecorder.Outbox() {
                public void add(String eventId, String tenantId, String json) {
                    if (auditBroken) throw new IllegalStateException("store down");
                    audited.add(json);
                }

                public List<Pending> pending(int limit) {
                    return List.of();
                }

                public void remove(String eventId) {}
            },
            (key, json) -> {},
            "approval-service",
            true);
    private final ApprovalApplicationService service =
            new ApprovalApplicationService(approvals, events, jobs, delegations, audit);

    @org.junit.jupiter.api.BeforeEach
    void membershipDirectory() {
        var staff = org.mockito.Mockito.mock(com.openhire.platform.security.TenantStaffDirectory.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "staff", staff);
    }

    @Test
    void snapshotsStructuredHeadcountAndGeneratesAnAccurateSeatDeltaTitle() {
        var details = new com.openhire.approval.dto.ApprovalDtos.HeadcountExceptionInput(
                "Site Reliability Engineer", 1, 2, "Two finalists; on-call rotation is short by one.");
        var input = new com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest(
                "HEADCOUNT_EXCEPTION",
                "job-sre",
                "SRE",
                List.of(new com.openhire.approval.dto.ApprovalDtos.StepInput(
                        "manager", "manager", "Hiring manager", 0, null)),
                null,
                null,
                null,
                null,
                details);
        when(approvals.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var detail = service.create("tenant-a", "recruiter", input);
        assertThat(detail.title()).isEqualTo("Site Reliability Engineer, +1 seat");
        assertThat(detail.headcountException()).isEqualTo(details);
        var captor = org.mockito.ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvals).save(captor.capture());
        var saved = captor.getValue();
        when(approvals.find("tenant-a", saved.id())).thenReturn(Optional.of(saved));
        assertThat(service.get("tenant-a", saved.id()).headcountException().requestedHeadcount())
                .isEqualTo(2);
        when(approvals.findAll("tenant-a")).thenReturn(List.of(saved));
        assertThat(service.list("tenant-a", "manager", "waiting_on_me", null)
                        .getFirst()
                        .title())
                .isEqualTo("Site Reliability Engineer, +1 seat");
        service.approve("tenant-a", saved.id(), "manager");
        verifyNoJobDecision();
    }

    @Test
    void unassignedSameRoleUserAndRequesterCannotDecideAndNoEventIsPublished() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.approve("tenant-a", request.id(), "another-manager"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> service.reject("tenant-a", request.id(), "No", "requester"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        org.mockito.Mockito.verifyNoInteractions(events);
        verify(approvals, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void roleRemovalOrForeignMembershipBlocksTheNamedAssigneeBeforePersistence() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));
        var staff = org.mockito.Mockito.mock(com.openhire.platform.security.TenantStaffDirectory.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "staff", staff);
        when(staff.require("tenant-a", "manager", "HIRING_MANAGER"))
                .thenThrow(new IllegalArgumentException("Active tenant membership required"));
        assertThatThrownBy(() -> service.approve("tenant-a", request.id(), "manager"))
                .hasMessageContaining("membership");
        verify(approvals, never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    @Test
    void genericWildcardAndRecruiterApprovalStepsCannotBeCreated() {
        for (var step : List.of(
                new com.openhire.approval.dto.ApprovalDtos.StepInput("s1", "me", "HIRING_MANAGER", 0, null),
                new com.openhire.approval.dto.ApprovalDtos.StepInput("s1", "another-user", "RECRUITER", 0, null))) {
            var input = new com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest(
                    "JOB_NEW", "job-1", "Job", List.of(step), null, null, null);
            assertThatThrownBy(() -> service.create("tenant-a", "requester", input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verify(approvals, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryOfRecordedJobDecisionIsRestrictedToTheDecisionMakerAndDoesNotEmailTwice() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));
        service.approve("tenant-a", request.id(), "manager");
        service.approve("tenant-a", request.id(), "manager");
        org.mockito.Mockito.verify(events, org.mockito.Mockito.times(1))
                .publish(
                        org.mockito.ArgumentMatchers.eq("approval.approved.v1"),
                                org.mockito.ArgumentMatchers.eq("tenant-a"),
                        org.mockito.ArgumentMatchers.eq(request.id()), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> service.approve("tenant-a", request.id(), "other-manager"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private void verifyNoJobDecision() {
        org.mockito.Mockito.verifyNoInteractions(jobs);
    }

    @Test
    void rejectsNonIncreasingHeadcountWithoutPersistingAnApproval() {
        var input = new com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest(
                "HEADCOUNT_EXCEPTION",
                "job-sre",
                "SRE",
                List.of(new com.openhire.approval.dto.ApprovalDtos.StepInput(
                        "manager", "manager", "Hiring manager", 0, null)),
                null,
                null,
                null,
                null,
                new com.openhire.approval.dto.ApprovalDtos.HeadcountExceptionInput("SRE", 2, 2, "Reason"));
        assertThatThrownBy(() -> service.create("tenant-a", "recruiter", input))
                .isInstanceOf(IllegalArgumentException.class);
        verify(approvals, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storesVersionComparisonWithoutInventingAnImpactAndPinsTheDecisionVersion() {
        var input = new com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest(
                "JOB_VERSION_CHANGE",
                "job-1",
                "Backend Engineer, version 3",
                List.of(new com.openhire.approval.dto.ApprovalDtos.StepInput(
                        "manager", "manager", "Hiring manager", 0, null)),
                List.of(new com.openhire.approval.dto.ApprovalDtos.FieldChangeInput("Description", "Old", "New")),
                null,
                List.of(),
                new com.openhire.approval.dto.ApprovalDtos.VersionComparison(1, 3));
        when(approvals.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var detail = service.create("tenant-a", "recruiter", input);
        assertThat(detail.comparison().previousVersion()).isEqualTo(1);
        assertThat(detail.comparison().proposedVersion()).isEqualTo(3);
        assertThat(detail.impact()).isNull();
        assertThat(detail.diff().getFirst().before()).isEqualTo("Old");
        var captor = org.mockito.ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvals).save(captor.capture());
        var request = captor.getValue();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));
        assertThat(service.get("tenant-a", request.id()).comparison()).isEqualTo(detail.comparison());
        service.approve("tenant-a", request.id(), "manager");
        verify(jobs).decision("tenant-a", "job-1", "APPROVED", null, "manager", 3);
    }

    @Test
    void rejectsInvalidComparisonBeforePersistence() {
        var input = new com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest(
                "JOB_VERSION_CHANGE",
                "job-1",
                "Backend Engineer",
                List.of(new com.openhire.approval.dto.ApprovalDtos.StepInput(
                        "manager", "manager", "Hiring manager", 0, null)),
                List.of(),
                null,
                List.of(),
                new com.openhire.approval.dto.ApprovalDtos.VersionComparison(3, 2));
        assertThatThrownBy(() -> service.create("tenant-a", "recruiter", input))
                .isInstanceOf(IllegalArgumentException.class);
        verify(approvals, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void neverReadsAnApprovalOutsideTheResolvedTenant() {
        when(approvals.find("tenant-b", "approval-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("tenant-b", "approval-1")).isInstanceOf(ResourceNotFoundException.class);

        verify(approvals).find("tenant-b", "approval-1");
        verify(approvals, never()).find("tenant-a", "approval-1");
    }

    @Test
    void auditsApprovalWithActorAndResource() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));

        service.approve("tenant-a", request.id(), "manager");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"APPROVAL_APPROVED\"", "\"tenantId\":\"tenant-a\"")
                .contains("\"id\":\"manager\"", "\"type\":\"Approval\"", "\"id\":\"" + request.id() + "\"")
                .contains("\"approvalType\":\"JOB_NEW\"")
                .doesNotContain("Backend Engineer");
    }

    @Test
    void auditsRejectionWithoutTheReasonText() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));

        service.reject("tenant-a", request.id(), "Candidate pool is too thin", "manager");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"APPROVAL_REJECTED\"", "\"status\":\"REJECTED\"")
                .doesNotContain("too thin");
    }

    @Test
    void auditsDelegationWithTheDelegateId() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));

        service.delegate("tenant-a", request.id(), "manager", "delegate-9");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"APPROVAL_DELEGATED\"", "\"id\":\"manager\"")
                .contains("\"delegateTo\":\"delegate-9\"");
    }

    @Test
    void doesNotAuditADecisionThatFailed() {
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.approve("tenant-a", request.id(), "someone-else"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(audited).isEmpty();
    }

    @Test
    void anAuditFailureNeverBreaksTheDecision() {
        auditBroken = true;
        var request = request();
        when(approvals.find("tenant-a", request.id())).thenReturn(Optional.of(request));

        service.approve("tenant-a", request.id(), "manager");

        verify(jobs).decision("tenant-a", "job-1", "APPROVED", null, "manager");
        assertThat(audited).isEmpty();
    }

    private ApprovalRequest request() {
        return ApprovalRequest.create(
                "tenant-a",
                "JOB_NEW",
                "job-1",
                "Backend Engineer",
                "requester",
                List.of(new ApprovalRequest.ApprovalStep(
                        "one", "manager", "Hiring manager", ApprovalRequest.StepStatus.PENDING, null)),
                List.of(),
                0,
                List.of());
    }
}
