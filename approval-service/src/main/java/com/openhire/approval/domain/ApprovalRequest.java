package com.openhire.approval.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("approval_requests")
@CompoundIndex(name = "tenant_status_created", def = "{'tenantId': 1, 'status': 1, 'createdAt': -1}")
public class ApprovalRequest {
    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String type;
    private String subjectId;
    private String title;
    private String requestedBy;
    private ApprovalStatus status;
    private String stepLabel;
    private List<ApprovalStep> steps;
    private List<FieldChange> diff;
    private Integer rescoredApplicants;
    private Integer previousVersion;
    private Integer proposedVersion;
    private HeadcountException headcountException;
    private List<Term> terms;
    private Instant createdAt;
    private Instant updatedAt;
    private String decisionReason;
    private String decisionActor;

    public String decisionActor() {
        return decisionActor;
    }

    public String decisionReason() {
        return decisionReason;
    }

    private Instant lastReminderAt;

    protected ApprovalRequest() {}

    public static ApprovalRequest create(
            String tenantId,
            String type,
            String subjectId,
            String title,
            String requestedBy,
            List<ApprovalStep> requestedSteps,
            List<FieldChange> diff,
            Integer rescoredApplicants,
            List<Term> terms) {
        if (requestedSteps == null || requestedSteps.isEmpty()) {
            throw new IllegalArgumentException("At least one approval step is required");
        }
        var now = Instant.now();
        var value = new ApprovalRequest();
        value.id = UUID.randomUUID().toString();
        value.tenantId = tenantId;
        value.type = type;
        value.subjectId = subjectId;
        value.title = title;
        value.requestedBy = requestedBy;
        value.status = ApprovalStatus.PENDING;
        value.steps = new ArrayList<>();
        var firstGroup =
                requestedSteps.stream().mapToInt(ApprovalStep::group).min().orElse(0);
        for (int i = 0; i < requestedSteps.size(); i++) {
            var step = requestedSteps.get(i);
            value.steps.add(new ApprovalStep(
                    step.id(),
                    step.approver(),
                    step.role(),
                    step.group(),
                    step.group() == firstGroup ? StepStatus.CURRENT : StepStatus.PENDING,
                    null,
                    step.dueAt(),
                    step.delegatedFrom()));
        }
        value.diff = diff == null ? List.of() : List.copyOf(diff);
        value.rescoredApplicants = rescoredApplicants;
        value.terms = terms == null ? List.of() : List.copyOf(terms);
        value.createdAt = now;
        value.updatedAt = now;
        value.refreshStepLabel();
        return value;
    }

    public void assignLegacy(String user, String role) {
        requirePending();
        var current = currentStep();
        if (!"me".equalsIgnoreCase(current.approver()) && !"RECRUITER".equalsIgnoreCase(current.role()))
            throw new IllegalStateException("This request already has a named approver");
        steps.set(
                steps.indexOf(current),
                new ApprovalStep(
                        current.id(),
                        user,
                        role,
                        current.group(),
                        current.status(),
                        current.at(),
                        current.dueAt(),
                        current.approver()));
        refreshStepLabel();
    }

    public void approve() {
        approveCurrent(currentStep());
    }

    public void approve(String actor) {
        var current = steps.stream()
                .filter(step -> step.status() == StepStatus.CURRENT)
                .filter(step -> step.approver().equals(actor) && !requestedBy.equals(actor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Approval is not assigned to " + actor));
        approveCurrent(current);
        decisionActor = actor;
    }

    private void approveCurrent(ApprovalStep current) {
        requirePending();
        var index = steps.indexOf(current);
        steps.set(index, current.withStatus(StepStatus.DONE, Instant.now()));
        if (steps.stream().noneMatch(step -> step.status() == StepStatus.CURRENT)) {
            var nextGroup = steps.stream()
                    .filter(step -> step.status() == StepStatus.PENDING)
                    .mapToInt(ApprovalStep::group)
                    .min();
            if (nextGroup.isPresent()) {
                for (int i = 0; i < steps.size(); i++) {
                    var next = steps.get(i);
                    if (next.group() == nextGroup.getAsInt()) steps.set(i, next.withStatus(StepStatus.CURRENT, null));
                }
            } else {
                status = ApprovalStatus.APPROVED;
            }
        }
        if (steps.stream().allMatch(step -> step.status() == StepStatus.DONE)) {
            status = ApprovalStatus.APPROVED;
        }
        decisionReason = null;
        updatedAt = Instant.now();
        refreshStepLabel();
    }

    public void reject(String reason) {
        requireReason(reason);
        requirePending();
        status = ApprovalStatus.REJECTED;
        decisionReason = reason.trim();
        updatedAt = Instant.now();
        stepLabel = "Rejected";
    }

    public void reject(String actor, String reason) {
        requireAssigned(actor);
        reject(reason);
        decisionActor = actor;
    }

    public void requestChanges(String reason) {
        requireReason(reason);
        requirePending();
        status = ApprovalStatus.CHANGES_REQUESTED;
        decisionReason = reason.trim();
        updatedAt = Instant.now();
        stepLabel = "Changes requested";
    }

    public void requestChanges(String actor, String reason) {
        requireAssigned(actor);
        requestChanges(reason);
        decisionActor = actor;
    }

    public void delegate(String approver) {
        requireReason(approver);
        requirePending();
        var current = currentStep();
        var index = steps.indexOf(current);
        steps.set(
                index,
                new ApprovalStep(
                        current.id(),
                        approver.trim(),
                        current.role(),
                        current.group(),
                        current.status(),
                        current.at(),
                        current.dueAt(),
                        current.approver()));
        updatedAt = Instant.now();
        refreshStepLabel();
    }

    public void delegate(String actor, String approver) {
        requireAssigned(actor);
        delegate(approver);
    }

    public void undo() {
        if (status == ApprovalStatus.APPROVED) {
            var last = steps.getLast();
            steps.set(steps.size() - 1, last.withStatus(StepStatus.CURRENT, null));
        } else if (status != ApprovalStatus.REJECTED) {
            throw new IllegalStateException("Only approved or rejected decisions can be undone");
        }
        status = ApprovalStatus.PENDING;
        decisionReason = null;
        updatedAt = Instant.now();
        refreshStepLabel();
    }

    private void requirePending() {
        if (status != ApprovalStatus.PENDING) throw new IllegalStateException("Approval is not pending");
    }

    private void requireReason(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A reason is required");
    }

    private ApprovalStep currentStep() {
        return steps.stream()
                .filter(step -> step.status() == StepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Approval has no current step"));
    }

    private void requireAssigned(String actor) {
        var assigned = steps.stream()
                .filter(step -> step.status() == StepStatus.CURRENT)
                .anyMatch(step -> step.approver().equals(actor) && !requestedBy.equals(actor));
        if (!assigned) throw new IllegalStateException("Approval is not assigned to " + actor);
    }

    private void refreshStepLabel() {
        if (status == ApprovalStatus.APPROVED) {
            stepLabel = "Approved";
            return;
        }
        var current = currentStep();
        stepLabel = "Waiting on " + current.approver();
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String type() {
        return type;
    }

    public String subjectId() {
        return subjectId;
    }

    public String title() {
        return title;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public ApprovalStatus status() {
        return status;
    }

    public String stepLabel() {
        return stepLabel;
    }

    public List<ApprovalStep> steps() {
        return List.copyOf(steps);
    }

    public List<FieldChange> diff() {
        return diff;
    }

    public Integer rescoredApplicants() {
        return rescoredApplicants;
    }

    public void compareVersions(int previous, int proposed) {
        if (!"JOB_VERSION_CHANGE".equals(type) || previous < 1 || proposed <= previous)
            throw new IllegalArgumentException("Invalid job version comparison");
        previousVersion = previous;
        proposedVersion = proposed;
    }

    public Integer previousVersion() {
        return previousVersion;
    }

    /** Snapshot request context only; approval does not silently alter a job's headcount. */
    public void describeHeadcount(String jobTitle, int current, int requested, String reason) {
        if (!"HEADCOUNT_EXCEPTION".equals(type)
                || jobTitle == null
                || jobTitle.isBlank()
                || current < 0
                || requested <= current
                || reason == null
                || reason.isBlank()
                || reason.length() > 4000)
            throw new IllegalArgumentException(
                    "A headcount exception needs a job title, an increased requested total and a reason");
        headcountException = new HeadcountException(jobTitle.trim(), current, requested, reason.trim());
        int additional = requested - current;
        title = jobTitle.trim() + ", +" + additional + (additional == 1 ? " seat" : " seats");
    }

    public HeadcountException headcountException() {
        return headcountException;
    }

    public record HeadcountException(String jobTitle, int currentHeadcount, int requestedHeadcount, String reason) {}

    public Integer proposedVersion() {
        return proposedVersion;
    }

    public List<Term> terms() {
        return terms;
    }

    public boolean overdue() {
        var now = Instant.now();
        return status == ApprovalStatus.PENDING
                && steps.stream()
                        .anyMatch(step -> step.status() == StepStatus.CURRENT
                                && step.dueAt() != null
                                && step.dueAt().isBefore(now));
    }

    public boolean reminderDue(Instant now) {
        return overdue() && (lastReminderAt == null || lastReminderAt.isBefore(now.minusSeconds(86_400)));
    }

    public void markReminderSent(Instant now) {
        lastReminderAt = now;
        updatedAt = now;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public enum StepStatus {
        DONE,
        CURRENT,
        PENDING
    }

    public record ApprovalStep(
            String id,
            String approver,
            String role,
            int group,
            StepStatus status,
            Instant at,
            Instant dueAt,
            String delegatedFrom) {
        public ApprovalStep(String id, String approver, String role, StepStatus status, Instant at) {
            this(id, approver, role, 0, status, at, null, null);
        }

        public ApprovalStep withStatus(StepStatus next, Instant decidedAt) {
            return new ApprovalStep(id, approver, role, group, next, decidedAt, dueAt, delegatedFrom);
        }
    }

    public record FieldChange(String field, String before, String after) {}

    public record Term(String label, String value) {}
}
