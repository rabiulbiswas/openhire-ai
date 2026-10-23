package com.openhire.jobservice.service;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.domain.Job.JobContent;
import com.openhire.jobservice.domain.JobStatus;
import com.openhire.jobservice.dto.request.AiJobDraftRequest;
import com.openhire.jobservice.dto.request.CreateJobRequest;
import com.openhire.jobservice.dto.request.JobCommands.HiringTeamMember;
import com.openhire.jobservice.dto.request.JobCommands.InterviewRound;
import com.openhire.jobservice.dto.response.AiJobDraftResponse;
import com.openhire.jobservice.dto.response.JobResponse;
import com.openhire.jobservice.dto.response.JobSummaryResponse;
import com.openhire.jobservice.dto.response.PageResponse;
import com.openhire.jobservice.service.port.ApprovalClient;
import com.openhire.jobservice.service.port.IdempotencyStore;
import com.openhire.jobservice.service.port.JobDescriptionGenerator;
import com.openhire.jobservice.service.port.JobRepository;
import com.openhire.jobservice.service.port.OutboxWriter;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.event.DomainEventEnvelope;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class JobApplicationService {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private final JobRepository jobs;
    private final OutboxWriter outbox;
    private final IdempotencyStore idempotency;
    private final JobDescriptionGenerator descriptionGenerator;
    private final ApprovalClient approvals;
    private final AuditRecorder audit;

    public JobApplicationService(
            JobRepository jobs,
            OutboxWriter outbox,
            IdempotencyStore idempotency,
            JobDescriptionGenerator descriptionGenerator,
            ApprovalClient approvals,
            AuditRecorder audit) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.descriptionGenerator = descriptionGenerator;
        this.approvals = approvals;
        this.audit = audit;
    }

    public JobResponse create(String tenantId, String actorId, String idempotencyKey, CreateJobRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existingId = idempotency.findResourceId(tenantId, idempotencyKey);
            if (existingId.isPresent()) {
                var existing = requireJob(tenantId, existingId.get());
                if ("PENDING_APPROVAL".equals(request.status()) && existing.status() == JobStatus.DRAFT) {
                    return submit(tenantId, existing.id(), actorId);
                }
                return JobResponse.from(existing);
            }
        }
        var job = Job.create(tenantId, content(request), JobStatus.DRAFT, actorId);
        var saved = jobs.save(job);
        emit("job.requisition.created.v1", saved);
        recordAudit("JOB_CREATED", saved, actorId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotency.remember(tenantId, idempotencyKey, saved.id(), IDEMPOTENCY_TTL);
        }
        if ("PENDING_APPROVAL".equals(request.status())) {
            saved.submit(actorId);
            saved = jobs.save(saved);
            approvals.requestJobApproval(tenantId, actorId, saved.id(), saved.title(), saved.currentVersion());
            emit("job.approval.requested.v1", saved);
        }
        return JobResponse.from(saved);
    }

    /** Compatibility overload for existing integrations and tests. */
    public JobResponse create(String tenantId, String idempotencyKey, CreateJobRequest request) {
        return create(tenantId, "system", idempotencyKey, request);
    }

    public JobResponse update(String tenantId, String id, int expectedVersion, String actor, CreateJobRequest request) {
        return change(
                tenantId,
                id,
                value -> value.edit(
                        expectedVersion,
                        content(request, value.currentSnapshot().interviewPlan()),
                        actor),
                "job.version.created.v1");
    }

    public JobResponse updateHiringTeam(
            String tenantId, String id, int expectedVersion, String actor, List<HiringTeamMember> members) {
        return change(
                tenantId,
                id,
                value -> value.updateHiringTeam(
                        expectedVersion,
                        members.stream()
                                .map(item -> new Job.HiringTeamMember(item.userId(), item.name(), item.role()))
                                .toList(),
                        actor),
                "job.hiring-team.updated.v1");
    }

    public JobResponse updateInterviewPlan(
            String tenantId, String id, int expectedVersion, String actor, List<InterviewRound> rounds) {
        return change(
                tenantId,
                id,
                value -> value.updateInterviewPlan(
                        expectedVersion,
                        rounds.stream()
                                .map(item -> new Job.InterviewRound(
                                        item.id(), item.name(), item.durationMin(), item.interviewerNames()))
                                .toList(),
                        actor),
                "job.interview-plan.updated.v1");
    }

    public JobResponse submit(String tenantId, String id, String actor) {
        var job = requireJob(tenantId, id);
        job.submit(actor);
        var saved = jobs.save(job);
        approvals.requestJobApproval(tenantId, actor, saved.id(), saved.title(), saved.currentVersion());
        emit("job.approval.requested.v1", saved);
        return JobResponse.from(saved);
    }

    public JobResponse decision(String tenantId, String id, String decision, String actor, String reason) {
        return switch (decision.toUpperCase()) {
            case "APPROVED" -> change(tenantId, id, value -> value.markApproved(actor), "job.approved.v1");
            case "CHANGES_REQUESTED", "REJECTED" ->
                change(
                        tenantId,
                        id,
                        value -> value.requestChanges(actor, reason == null ? decision : reason),
                        "job.changes-requested.v1");
            default -> throw new IllegalArgumentException("Unsupported approval decision " + decision);
        };
    }

    public JobResponse publish(String tenantId, String id, String actor) {
        var response = change(tenantId, id, value -> value.publish(actor), "job.published.v1");
        recordAudit(
                "JOB_PUBLISHED",
                tenantId,
                response.id(),
                response.currentVersion().version(),
                actor);
        return response;
    }

    public JobResponse pause(String tenantId, String id, String actor) {
        return change(tenantId, id, value -> value.pause(actor), "job.paused.v1");
    }

    public JobResponse close(String tenantId, String id, String actor) {
        var response = change(tenantId, id, value -> value.close(actor), "job.closed.v1");
        recordAudit(
                "JOB_CLOSED", tenantId, response.id(), response.currentVersion().version(), actor);
        return response;
    }

    public JobResponse reopen(String tenantId, String id, String actor) {
        return change(tenantId, id, value -> value.reopen(actor), "job.reopened.v1");
    }

    public PageResponse<JobSummaryResponse> list(String tenantId, String status) {
        var data = jobs.findAll(tenantId).stream()
                .filter(job -> status == null
                        || status.isBlank()
                        || job.status().name().equals(status))
                .map(JobSummaryResponse::from)
                .toList();
        return PageResponse.singlePage(data);
    }

    public JobResponse get(String tenantId, String id) {
        return JobResponse.from(requireJob(tenantId, id));
    }

    public AiJobDraftResponse draft(AiJobDraftRequest request) {
        return descriptionGenerator.generate(request.normalized());
    }

    public AiJobDraftResponse draft(String tenantId, String id, int version, String tone) {
        var job = requireJob(tenantId, id);
        var snapshot = job.versions().stream()
                .filter(value -> value.version() == version)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Job version " + version + " was not found"));
        return descriptionGenerator.generate(
                new AiJobDraftRequest(job.title(), job.team(), job.level(), snapshot.mustHaveSkills(), tone)
                        .normalized());
    }

    private JobResponse change(String tenantId, String id, Consumer<Job> command, String eventType) {
        var value = requireJob(tenantId, id);
        command.accept(value);
        var saved = jobs.save(value);
        emit(eventType, saved);
        return JobResponse.from(saved);
    }

    private void recordAudit(String action, Job job, String actor) {
        recordAudit(action, job.tenantId(), job.id(), job.currentVersion(), actor);
    }

    private void recordAudit(String action, String tenantId, String jobId, int version, String actor) {
        audit.record(AuditRecord.of(tenantId, action)
                .user(actor, null)
                .resource("Job", jobId)
                .detail("version", version)
                .build());
    }

    private void emit(String type, Job job) {
        outbox.append(
                "job.events.v1",
                DomainEventEnvelope.create(
                        type,
                        job.tenantId(),
                        "Job",
                        job.id(),
                        UUID.randomUUID().toString(),
                        Map.of("jobId", job.id(), "status", job.status().name(), "version", job.currentVersion())));
    }

    private Job requireJob(String tenantId, String id) {
        return jobs.findById(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Job " + id + " was not found"));
    }

    private JobContent content(CreateJobRequest request) {
        return content(request, List.of());
    }

    private JobContent content(CreateJobRequest request, List<Job.InterviewRound> interviewPlan) {
        return new JobContent(
                request.title(),
                request.team(),
                request.level(),
                request.location(),
                request.salaryBand().min(),
                request.salaryBand().max(),
                request.salaryBand().currency(),
                request.mustHaveSkills(),
                request.knockoutQuestions() == null
                        ? List.of()
                        : request.knockoutQuestions().stream()
                                .map(value ->
                                        new Job.KnockoutQuestion(value.id(), value.question(), value.requiredAnswer()))
                                .toList(),
                request.rubric() == null
                        ? List.of()
                        : request.rubric().stream()
                                .map(value -> new Job.RubricCriterion(value.id(), value.label(), value.weight()))
                                .toList(),
                request.description(),
                interviewPlan);
    }
}
