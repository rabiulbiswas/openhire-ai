package com.openhire.approval.application;

import com.openhire.approval.api.ApprovalDtos.ApprovalCounts;
import com.openhire.approval.api.ApprovalDtos.ApprovalDetail;
import com.openhire.approval.api.ApprovalDtos.ApprovalSummary;
import com.openhire.approval.api.ApprovalDtos.CreateApprovalRequest;
import com.openhire.approval.application.port.ApprovalEventPublisher;
import com.openhire.approval.application.port.ApprovalRepository;
import com.openhire.approval.application.port.DelegationResolver;
import com.openhire.approval.application.port.JobWorkflowClient;
import com.openhire.approval.domain.ApprovalRequest;
import com.openhire.approval.domain.ApprovalStatus;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ApprovalApplicationService {
    private final ApprovalRepository approvals;
    private final ApprovalEventPublisher events;
    private final JobWorkflowClient jobs;
    private final DelegationResolver delegations;
    private final AuditRecorder audit;

    public ApprovalApplicationService(
            ApprovalRepository approvals,
            ApprovalEventPublisher events,
            JobWorkflowClient jobs,
            DelegationResolver delegations,
            AuditRecorder audit) {
        this.approvals = approvals;
        this.events = events;
        this.jobs = jobs;
        this.delegations = delegations;
        this.audit = audit;
    }

    public ApprovalDetail create(String tenantId, String actorId, CreateApprovalRequest input) {
        var now = java.time.Instant.now();
        var steps = input.steps().stream()
                .map(step -> {
                    var approver = delegations.resolve(tenantId, step.approver(), now);
                    return new ApprovalRequest.ApprovalStep(
                            step.id(),
                            approver,
                            step.role(),
                            step.group() == null ? input.steps().indexOf(step) : step.group(),
                            ApprovalRequest.StepStatus.PENDING,
                            null,
                            step.slaHours() == null ? null : now.plus(java.time.Duration.ofHours(step.slaHours())),
                            approver.equals(step.approver()) ? null : step.approver());
                })
                .toList();
        var value = approvals.save(ApprovalRequest.create(
                tenantId,
                input.type(),
                input.subjectId(),
                input.title(),
                actorId,
                steps,
                input.diff() == null
                        ? List.of()
                        : input.diff().stream()
                                .map(item -> new ApprovalRequest.FieldChange(item.field(), item.before(), item.after()))
                                .toList(),
                input.impact() == null ? null : input.impact().rescoredApplicants(),
                input.terms() == null
                        ? List.of()
                        : input.terms().stream()
                                .map(item -> new ApprovalRequest.Term(item.label(), item.value()))
                                .toList()));
        publish("approval.requested.v1", value);
        return ApprovalDetail.from(value);
    }

    public List<ApprovalSummary> list(String tenantId, String actorId, String scope, String status) {
        return approvals.findAll(tenantId).stream()
                .filter(value -> status == null
                        || status.isBlank()
                        || value.status().name().equals(status))
                .filter(value -> matchesScope(value, actorId, scope))
                .map(ApprovalSummary::from)
                .toList();
    }

    public ApprovalDetail get(String tenantId, String id) {
        return ApprovalDetail.from(require(tenantId, id));
    }

    public ApprovalCounts counts(String tenantId, String actorId) {
        var all = approvals.findAll(tenantId);
        return new ApprovalCounts(
                count(all, value -> value.status() == ApprovalStatus.PENDING && isAssigned(value, actorId)),
                count(all, value -> value.requestedBy().equals(actorId)),
                count(
                        all,
                        value -> value.status() == ApprovalStatus.APPROVED
                                || value.status() == ApprovalStatus.REJECTED));
    }

    public List<ApprovalSummary> overdue(String tenantId) {
        return approvals.findAll(tenantId).stream()
                .filter(ApprovalRequest::overdue)
                .map(ApprovalSummary::from)
                .toList();
    }

    public int publishDueReminders() {
        var now = java.time.Instant.now();
        var due = approvals.findAllTenants().stream()
                .filter(value -> value.reminderDue(now))
                .toList();
        due.forEach(value -> {
            publish("approval.sla-reminder.v1", value);
            value.markReminderSent(now);
            approvals.save(value);
        });
        return due.size();
    }

    public void approve(String tenantId, String id, String actor) {
        var existing = require(tenantId, id);
        if (existing.status() == ApprovalStatus.APPROVED) {
            if (existing.type().startsWith("JOB_")) {
                jobs.decision(tenantId, existing.subjectId(), "APPROVED", null, actor);
            }
            return;
        }
        var value = change(tenantId, id, item -> item.approve(actor), "approval.approved.v1");
        recordAudit("APPROVAL_APPROVED", value, actor, null);
        if (value.status() == ApprovalStatus.APPROVED && value.type().startsWith("JOB_")) {
            jobs.decision(tenantId, value.subjectId(), "APPROVED", null, actor);
        }
    }

    public void reject(String tenantId, String id, String reason, String actor) {
        var value = change(tenantId, id, item -> item.reject(actor, reason), "approval.rejected.v1");
        recordAudit("APPROVAL_REJECTED", value, actor, null);
        if (value.type().startsWith("JOB_")) jobs.decision(tenantId, value.subjectId(), "REJECTED", reason, actor);
    }

    public void requestChanges(String tenantId, String id, String reason, String actor) {
        var value = change(tenantId, id, item -> item.requestChanges(actor, reason), "approval.changes-requested.v1");
        if (value.type().startsWith("JOB_"))
            jobs.decision(tenantId, value.subjectId(), "CHANGES_REQUESTED", reason, actor);
    }

    public void undo(String tenantId, String id) {
        change(tenantId, id, ApprovalRequest::undo, "approval.decision-undone.v1");
    }

    public void delegate(String tenantId, String id, String actor, String toUser) {
        var value = change(tenantId, id, item -> item.delegate(actor, toUser), "approval.delegated.v1");
        recordAudit("APPROVAL_DELEGATED", value, actor, toUser);
    }

    private void recordAudit(String action, ApprovalRequest value, String actor, String delegateTo) {
        var record = AuditRecord.of(value.tenantId(), action)
                .user(actor, null)
                .resource("Approval", value.id())
                .detail("approvalType", value.type())
                .detail("status", value.status().name());
        if (delegateTo != null) record.detail("delegateTo", delegateTo);
        audit.record(record.build());
    }

    private ApprovalRequest change(
            String tenantId, String id, java.util.function.Consumer<ApprovalRequest> command, String event) {
        var value = require(tenantId, id);
        command.accept(value);
        approvals.save(value);
        publish(event, value);
        return value;
    }

    private ApprovalRequest require(String tenantId, String id) {
        return approvals
                .find(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Approval " + id + " was not found"));
    }

    private void publish(String event, ApprovalRequest value) {
        // Current approvers let consumers notify the right people; "me" marks a step anyone with the role may take.
        var current = value.steps().stream()
                .filter(step -> step.status() == ApprovalRequest.StepStatus.CURRENT)
                .map(ApprovalRequest.ApprovalStep::approver)
                .distinct()
                .toList();
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("approvalId", value.id());
        payload.put("subjectId", value.subjectId());
        payload.put("status", value.status().name());
        payload.put("type", value.type() == null ? "" : value.type());
        payload.put("title", value.title() == null ? "" : value.title());
        payload.put("requestedBy", value.requestedBy());
        payload.put("currentApprovers", current);
        events.publish(event, value.tenantId(), value.id(), payload);
    }

    private boolean matchesScope(ApprovalRequest value, String actorId, String scope) {
        if (scope == null || scope.isBlank()) return true;
        return switch (scope) {
            case "waiting_on_me" -> value.status() == ApprovalStatus.PENDING && isAssigned(value, actorId);
            case "requested_by_me" -> value.requestedBy().equals(actorId);
            case "completed" -> value.status() == ApprovalStatus.APPROVED || value.status() == ApprovalStatus.REJECTED;
            default -> true;
        };
    }

    private boolean isAssigned(ApprovalRequest value, String actorId) {
        return value.steps().stream()
                .anyMatch(step -> step.status() == ApprovalRequest.StepStatus.CURRENT
                        && (step.approver().equals(actorId) || "me".equalsIgnoreCase(step.approver())));
    }

    private long count(List<ApprovalRequest> values, java.util.function.Predicate<ApprovalRequest> predicate) {
        return values.stream().filter(predicate).count();
    }
}
