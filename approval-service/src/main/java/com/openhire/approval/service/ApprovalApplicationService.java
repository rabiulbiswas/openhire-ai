package com.openhire.approval.service;

import com.openhire.approval.domain.ApprovalRequest;
import com.openhire.approval.domain.ApprovalStatus;
import com.openhire.approval.dto.ApprovalDtos.ApprovalCounts;
import com.openhire.approval.dto.ApprovalDtos.ApprovalDetail;
import com.openhire.approval.dto.ApprovalDtos.ApprovalSummary;
import com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest;
import com.openhire.approval.service.port.ApprovalEventPublisher;
import com.openhire.approval.service.port.ApprovalRepository;
import com.openhire.approval.service.port.DelegationResolver;
import com.openhire.approval.service.port.JobWorkflowClient;
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.platform.security.TenantStaffDirectory staff;

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
        verifyActor(tenantId, actorId);
        var now = java.time.Instant.now();
        var steps = input.steps().stream()
                .map(step -> {
                    if (actorId.equals(step.approver()))
                        throw new IllegalArgumentException("You cannot approve your own request");
                    validateAssignee(tenantId, step.approver(), step.role());
                    var approver = delegations.resolve(tenantId, step.approver(), now);
                    if (actorId.equals(approver))
                        throw new IllegalArgumentException("You cannot approve your own request");
                    validateAssignee(tenantId, approver, step.role());
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
        var request = ApprovalRequest.create(
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
                                .toList());
        if (input.comparison() != null)
            request.compareVersions(
                    input.comparison().previousVersion(), input.comparison().proposedVersion());
        if (input.headcountException() != null) {
            var details = input.headcountException();
            if (details.currentHeadcount() == null || details.requestedHeadcount() == null)
                throw new IllegalArgumentException("Both recorded headcount totals are required");
            request.describeHeadcount(
                    details.jobTitle(), details.currentHeadcount(), details.requestedHeadcount(), details.reason());
        }
        var value = approvals.save(request);
        publish("approval.requested.v1", value);
        return ApprovalDetail.from(value);
    }

    public List<ApprovalSummary> list(String tenantId, String actorId, String scope, String status) {
        verifyActor(tenantId, actorId);
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
            authoriseRecordedDecision(existing, actor);
            if (existing.type().startsWith("JOB_")) {
                jobDecision(existing, "APPROVED", null, actor);
            }
            return;
        }
        authorise(existing, actor);
        var value = change(tenantId, id, item -> item.approve(actor), "approval.approved.v1");
        recordAudit("APPROVAL_APPROVED", value, actor, null);
        if (value.status() == ApprovalStatus.APPROVED && value.type().startsWith("JOB_")) {
            jobDecision(value, "APPROVED", null, actor);
        }
    }

    public void reject(String tenantId, String id, String reason, String actor) {
        var existing = require(tenantId, id);
        if (existing.status() == ApprovalStatus.REJECTED) {
            authoriseRecordedDecision(existing, actor);
            if (existing.type().startsWith("JOB_")) jobDecision(existing, "REJECTED", existing.decisionReason(), actor);
            return;
        }
        authorise(require(tenantId, id), actor);
        var value = change(tenantId, id, item -> item.reject(actor, reason), "approval.rejected.v1");
        recordAudit("APPROVAL_REJECTED", value, actor, null);
        if (value.type().startsWith("JOB_")) jobDecision(value, "REJECTED", reason, actor);
    }

    public void requestChanges(String tenantId, String id, String reason, String actor) {
        var existing = require(tenantId, id);
        if (existing.status() == ApprovalStatus.CHANGES_REQUESTED) {
            authoriseRecordedDecision(existing, actor);
            if (existing.type().startsWith("JOB_"))
                jobDecision(existing, "CHANGES_REQUESTED", existing.decisionReason(), actor);
            return;
        }
        authorise(require(tenantId, id), actor);
        var value = change(tenantId, id, item -> item.requestChanges(actor, reason), "approval.changes-requested.v1");
        if (value.type().startsWith("JOB_")) jobDecision(value, "CHANGES_REQUESTED", reason, actor);
    }

    public void undo(String tenantId, String id) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Submit a new approval request to revise a recorded decision");
    }

    private void jobDecision(ApprovalRequest value, String decision, String reason, String actor) {
        if (value.proposedVersion() == null)
            jobs.decision(value.tenantId(), value.subjectId(), decision, reason, actor);
        else jobs.decision(value.tenantId(), value.subjectId(), decision, reason, actor, value.proposedVersion());
    }

    public void delegate(String tenantId, String id, String actor, String toUser) {
        var request = require(tenantId, id);
        authorise(request, actor);
        if (request.requestedBy().equals(toUser))
            throw new IllegalArgumentException("The requester cannot approve their own request");
        request.steps().stream()
                .filter(s -> s.status() == ApprovalRequest.StepStatus.CURRENT
                        && s.approver().equals(actor))
                .forEach(s -> validateAssignee(tenantId, toUser, s.role()));
        var value = change(tenantId, id, item -> item.delegate(actor, toUser), "approval.delegated.v1");
        recordAudit("APPROVAL_DELEGATED", value, actor, toUser);
    }

    public void assignLegacy(
            String tenant, String id, String actor, String assignee, String role, boolean administrator) {
        verifyActor(tenant, actor);
        var request = require(tenant, id);
        if (!request.requestedBy().equals(actor) && !administrator)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        var step = request.steps().stream()
                .filter(s -> s.status() == ApprovalRequest.StepStatus.CURRENT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No approval is pending"));
        if (request.requestedBy().equals(assignee))
            throw new IllegalArgumentException("The requester cannot approve their own request");
        var assignedRole = "RECRUITER".equalsIgnoreCase(step.role()) ? role : step.role();
        if (assignedRole == null || assignedRole.isBlank())
            throw new IllegalArgumentException("Choose the approver role");
        validateAssignee(tenant, assignee, assignedRole);
        change(tenant, id, item -> item.assignLegacy(assignee, assignedRole), "approval.delegated.v1");
        recordAudit("APPROVAL_ASSIGNED", request, actor, assignee);
    }

    private void validateAssignee(String tenant, String id, String role) {
        var normalized = role.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!java.util.Set.of("HIRING_MANAGER", "HR_ADMIN", "ORG_ADMIN").contains(normalized))
            throw new IllegalArgumentException("Approval role must be Hiring manager, HR admin or Organisation admin");
        if (id == null || id.isBlank() || "me".equalsIgnoreCase(id))
            throw new IllegalArgumentException("Select a named approver before submitting");
        staff.require(tenant, id, normalized);
    }

    private void authoriseRecordedDecision(ApprovalRequest request, String actor) {
        verifyActor(request.tenantId(), actor);
        if (!actor.equals(request.decisionActor()) || request.requestedBy().equals(actor))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN);
        var step = request.steps().stream()
                .filter(s -> s.approver().equals(actor))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN));
        validateAssignee(request.tenantId(), actor, step.role());
    }

    private void authorise(ApprovalRequest request, String actor) {
        verifyActor(request.tenantId(), actor);
        if (request.requestedBy().equals(actor))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "You cannot approve your own request");
        var step = request.steps().stream()
                .filter(s -> s.status() == ApprovalRequest.StepStatus.CURRENT
                        && s.approver().equals(actor))
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "This request is not assigned to you"));
        validateAssignee(request.tenantId(), actor, step.role());
    }

    private static void verifyActor(String tenant, String actor) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth
                        instanceof
                        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt
                && (!actor.equals(jwt.getName())
                        || !tenant.equals(com.openhire.platform.tenant.TenantClaims.resolve(
                                jwt.getToken().getClaims()))))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Actor and organisation must match the authenticated user");
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
        // Only named participants are emitted; legacy placeholders are never recipient groups.
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
        payload.put(
                "participants",
                value.steps().stream()
                        .map(ApprovalRequest.ApprovalStep::approver)
                        .filter(id -> !"me".equalsIgnoreCase(id))
                        .distinct()
                        .toList());
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
                        && step.approver().equals(actorId)
                        && !value.requestedBy().equals(actorId));
    }

    private long count(List<ApprovalRequest> values, java.util.function.Predicate<ApprovalRequest> predicate) {
        return values.stream().filter(predicate).count();
    }
}
