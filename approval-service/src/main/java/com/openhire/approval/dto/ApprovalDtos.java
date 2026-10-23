package com.openhire.approval.dto;

import com.openhire.approval.domain.ApprovalRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;

public final class ApprovalDtos {
    private ApprovalDtos() {}

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
        public static <T> Page<T> of(List<T> values) {
            return new Page<>(List.copyOf(values), null, false);
        }
    }

    public record CreateApprovalRequest(
            @NotBlank String type,
            @NotBlank String subjectId,
            @NotBlank String title,
            @NotEmpty List<@Valid StepInput> steps,
            List<@Valid FieldChangeInput> diff,
            ImpactInput impact,
            List<@Valid TermInput> terms) {}

    public record StepInput(
            @NotBlank String id, @NotBlank String approver, @NotBlank String role, Integer group, Integer slaHours) {}

    public record FieldChangeInput(@NotBlank String field, @NotBlank String before, @NotBlank String after) {}

    public record ImpactInput(int rescoredApplicants) {}

    public record TermInput(@NotBlank String label, @NotBlank String value) {}

    public record ReasonRequest(@NotBlank String reason) {}

    public record DelegationRequest(@NotBlank String id, @NotBlank String toUser, Instant until) {}

    public record ApprovalCounts(long waitingOnMe, long requestedByMe, long completed) {}

    public record ApprovalSummary(
            String id,
            String type,
            String title,
            String requestedBy,
            Instant createdAt,
            String status,
            String stepLabel) {
        public static ApprovalSummary from(ApprovalRequest value) {
            return new ApprovalSummary(
                    value.id(),
                    value.type(),
                    value.title(),
                    value.requestedBy(),
                    value.createdAt(),
                    value.status().name(),
                    value.stepLabel());
        }
    }

    public record ApprovalDetail(
            String id,
            String type,
            String title,
            String requestedBy,
            Instant createdAt,
            String status,
            String stepLabel,
            List<ApprovalStep> steps,
            List<FieldChange> diff,
            Impact impact,
            List<Term> terms) {
        public static ApprovalDetail from(ApprovalRequest value) {
            return new ApprovalDetail(
                    value.id(),
                    value.type(),
                    value.title(),
                    value.requestedBy(),
                    value.createdAt(),
                    value.status().name(),
                    value.stepLabel(),
                    value.steps().stream()
                            .map(step -> new ApprovalStep(
                                    step.id(),
                                    step.approver(),
                                    step.role(),
                                    step.group(),
                                    step.status().name(),
                                    step.at(),
                                    step.dueAt(),
                                    step.delegatedFrom()))
                            .toList(),
                    value.diff().stream()
                            .map(item -> new FieldChange(item.field(), item.before(), item.after()))
                            .toList(),
                    value.rescoredApplicants() == null ? null : new Impact(value.rescoredApplicants()),
                    value.terms().stream()
                            .map(item -> new Term(item.label(), item.value()))
                            .toList());
        }
    }

    public record ApprovalStep(
            String id,
            String approver,
            String role,
            int group,
            String status,
            Instant at,
            Instant dueAt,
            String delegatedFrom) {}

    public record FieldChange(String field, String before, String after) {}

    public record Impact(int rescoredApplicants) {}

    public record Term(String label, String value) {}
}
