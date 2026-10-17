package com.openhire.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openhire.approval.application.port.ApprovalEventPublisher;
import com.openhire.approval.application.port.ApprovalRepository;
import com.openhire.approval.application.port.DelegationResolver;
import com.openhire.approval.application.port.JobWorkflowClient;
import com.openhire.approval.domain.ApprovalRequest;
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
                .isInstanceOf(IllegalStateException.class);

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
