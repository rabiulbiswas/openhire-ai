package com.openhire.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovalRequestTest {
    @Test
    void sequentialChainAdvancesThenCompletes() {
        var request = request();

        request.approve();
        assertThat(request.status()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(request.steps().get(0).status()).isEqualTo(ApprovalRequest.StepStatus.DONE);
        assertThat(request.steps().get(1).status()).isEqualTo(ApprovalRequest.StepStatus.CURRENT);

        request.approve();
        assertThat(request.status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void rejectedRequestCannotBeApprovedWithoutUndo() {
        var request = request();
        request.reject("Headcount is unavailable");

        assertThatThrownBy(request::approve).isInstanceOf(IllegalStateException.class);
        request.undo();
        request.approve();
        assertThat(request.status()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void rejectionRequiresReason() {
        assertThatThrownBy(() -> request().reject(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheAssignedActorCanApproveAConcreteStep() {
        var request = request();

        assertThatThrownBy(() -> request.approve("someone-else"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not assigned");
        request.approve("manager");

        assertThat(request.steps().get(0).status()).isEqualTo(ApprovalRequest.StepStatus.DONE);
    }

    @Test
    void onlyTheAssignedActorCanRejectOrDelegateAConcreteStep() {
        var rejection = request();
        var delegation = request();

        assertThatThrownBy(() -> rejection.reject("someone-else", "No headcount"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delegation.delegate("someone-else", "delegate"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parallelGroupCompletesBeforeTheNextGroupStarts() {
        var request = ApprovalRequest.create(
                "tenant-a",
                "JOB_NEW",
                "job-1",
                "Backend Engineer",
                "requester",
                List.of(
                        new ApprovalRequest.ApprovalStep(
                                "one", "manager", "Manager", 0, ApprovalRequest.StepStatus.PENDING, null, null, null),
                        new ApprovalRequest.ApprovalStep(
                                "two", "finance", "Finance", 0, ApprovalRequest.StepStatus.PENDING, null, null, null),
                        new ApprovalRequest.ApprovalStep(
                                "three", "hr", "HR", 1, ApprovalRequest.StepStatus.PENDING, null, null, null)),
                List.of(),
                0,
                List.of());

        request.approve();
        assertThat(request.steps().get(1).status()).isEqualTo(ApprovalRequest.StepStatus.CURRENT);
        assertThat(request.steps().get(2).status()).isEqualTo(ApprovalRequest.StepStatus.PENDING);
        request.approve();
        assertThat(request.steps().get(2).status()).isEqualTo(ApprovalRequest.StepStatus.CURRENT);
    }

    private ApprovalRequest request() {
        return ApprovalRequest.create(
                "tenant-a",
                "JOB_NEW",
                "job-1",
                "Backend Engineer",
                "requester",
                List.of(
                        new ApprovalRequest.ApprovalStep(
                                "one", "manager", "Hiring manager", ApprovalRequest.StepStatus.PENDING, null),
                        new ApprovalRequest.ApprovalStep(
                                "two", "hr", "HR admin", ApprovalRequest.StepStatus.PENDING, null)),
                List.of(),
                0,
                List.of());
    }
}
