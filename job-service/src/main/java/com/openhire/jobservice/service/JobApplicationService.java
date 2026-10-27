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
    private final com.openhire.jobservice.service.port.JobMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.platform.security.TenantStaffDirectory staff;

    @org.springframework.beans.factory.annotation.Autowired
    private JobCategories categories;

    public JobApplicationService(
            JobRepository jobs,
            OutboxWriter outbox,
            IdempotencyStore idempotency,
            JobDescriptionGenerator descriptionGenerator,
            ApprovalClient approvals,
            AuditRecorder audit,
            com.openhire.jobservice.service.port.JobMetrics metrics) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.descriptionGenerator = descriptionGenerator;
        this.approvals = approvals;
        this.audit = audit;
        this.metrics = metrics;
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
        if (request.qualification() == null || request.qualification().isBlank())
            throw new IllegalArgumentException("Qualification is required for new jobs");
        var job = Job.create(tenantId, content(tenantId, request, List.of(), null), JobStatus.DRAFT, actorId);
        assignManager(job, request.hiringManagerId(), actorId);
        if ("PENDING_APPROVAL".equals(request.status())) manager(job, actorId);
        var saved = jobs.save(job);
        emit("job.requisition.created.v1", saved);
        recordAudit("JOB_CREATED", saved, actorId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotency.remember(tenantId, idempotencyKey, saved.id(), IDEMPOTENCY_TTL);
        }
        if ("PENDING_APPROVAL".equals(request.status())) {
            return submitJob(tenantId, saved, actorId);
        }
        return JobResponse.from(saved);
    }

    /** Compatibility overload for existing integrations and tests. */
    public JobResponse create(String tenantId, String idempotencyKey, CreateJobRequest request) {
        return create(tenantId, "system", idempotencyKey, request);
    }

    public JobResponse update(String tenantId, String id, int expectedVersion, String actor, CreateJobRequest request) {
        // Validate before making any persistent change.
        if (request.hiringManagerId() != null) staff.require(tenantId, request.hiringManagerId(), "HIRING_MANAGER");
        return change(
                tenantId,
                id,
                value -> {
                    value.edit(
                            expectedVersion,
                            content(
                                    tenantId,
                                    request,
                                    value.currentSnapshot().interviewPlan(),
                                    value.currentSnapshot()),
                            actor);
                    assignManager(value, request.hiringManagerId(), actor);
                },
                "job.version.created.v1");
    }

    public JobResponse updateHiringTeam(
            String tenantId, String id, int expectedVersion, String actor, List<HiringTeamMember> members) {
        var managers = members.stream()
                .filter(m -> "HIRING_MANAGER".equalsIgnoreCase(m.role().replace(' ', '_')))
                .toList();
        if (managers.size() > 1)
            throw new IllegalArgumentException("Select one responsible hiring manager for approval");
        var job = requireJob(tenantId, id);
        if (job.status() == JobStatus.PENDING_APPROVAL
                && !job.hiringTeam().stream()
                        .filter(m -> "HIRING_MANAGER".equalsIgnoreCase(m.role().replace(' ', '_')))
                        .map(Job.HiringTeamMember::userId)
                        .toList()
                        .equals(managers.stream().map(HiringTeamMember::userId).toList()))
            throw new IllegalStateException("The hiring manager is frozen while approval is pending");
        managers.forEach(m -> staff.require(tenantId, m.userId(), "HIRING_MANAGER"));
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
        return submitJob(tenantId, job, actor);
    }

    private JobResponse submitJob(String tenantId, Job job, String actor) {
        var approver = manager(job, actor);
        var baseline = job.submittedVersion();
        job.submit(actor);
        var saved = job;
        if (baseline > 0 && baseline < saved.currentVersion()) {
            var before = saved.versions().stream()
                    .filter(v -> v.version() == baseline)
                    .findFirst()
                    .orElseThrow();
            approvals.requestAssignedJobChangeApproval(
                    tenantId,
                    actor,
                    saved.id(),
                    saved.title(),
                    new ApprovalClient.Comparison(baseline, saved.currentVersion()),
                    JobApprovalChanges.between(before, saved.currentSnapshot()),
                    approver);
        } else
            approvals.requestAssignedJobApproval(
                    tenantId, actor, saved.id(), saved.title(), saved.currentVersion(), approvalTerms(saved), approver);
        saved = jobs.save(saved);
        emit("job.approval.requested.v1", saved);
        return JobResponse.from(saved);
    }

    private void assignManager(Job job, String id, String actor) {
        if (id == null) return;
        var member = staff.require(job.tenantId(), id, "HIRING_MANAGER");
        var team = new java.util.ArrayList<>(job.hiringTeam().stream()
                .filter(m -> !"HIRING_MANAGER".equalsIgnoreCase(m.role().replace(' ', '_')))
                .toList());
        team.add(new Job.HiringTeamMember(member.id(), member.displayName(), "HIRING_MANAGER"));
        job.updateHiringTeam(job.currentVersion(), team, actor);
    }

    private String manager(Job job, String actor) {
        var managers = job.hiringTeam().stream()
                .filter(m -> "HIRING_MANAGER".equalsIgnoreCase(m.role().replace(' ', '_')))
                .toList();
        if (managers.size() != 1)
            throw new IllegalArgumentException("Select exactly one hiring manager before submitting");
        var id = managers.getFirst().userId();
        if (id.equals(actor))
            throw new IllegalArgumentException("A different hiring manager must approve your request");
        staff.require(job.tenantId(), id, "HIRING_MANAGER");
        return id;
    }

    /** Snapshot submitted values: later job edits must not silently change an approval's context. */
    private static List<ApprovalClient.Term> approvalTerms(Job job) {
        var salary = job.salaryMin() != null && job.salaryMax() != null
                ? job.currency() + " " + job.salaryMin().stripTrailingZeros().toPlainString() + " to "
                        + job.salaryMax().stripTrailingZeros().toPlainString()
                : "Not provided";
        var terms = new java.util.ArrayList<>(List.of(
                new ApprovalClient.Term(
                        "Headcount",
                        job.currentSnapshot().headcount() == null
                                ? "Not provided"
                                : job.currentSnapshot().headcount()
                                        + (job.currentSnapshot().positionType() == null
                                                ? ""
                                                : ", "
                                                        + ("REPLACEMENT"
                                                                        .equals(job.currentSnapshot()
                                                                                .positionType())
                                                                ? "replacement"
                                                                : "new position"))),
                new ApprovalClient.Term("Salary band", salary),
                new ApprovalClient.Term("Location", job.location()),
                new ApprovalClient.Term(
                        "Job category",
                        job.currentSnapshot().classification() == null
                                ? "Not specified"
                                : job.currentSnapshot().classification().categoryName()),
                new ApprovalClient.Term(
                        "Work mode",
                        job.currentSnapshot().classification() == null
                                ? "Not specified"
                                : job.currentSnapshot().classification().workMode()),
                new ApprovalClient.Term(
                        "Reason",
                        job.currentSnapshot().hiringReason() == null
                                ? "Not provided"
                                : job.currentSnapshot().hiringReason()),
                new ApprovalClient.Term("Job description", job.description())));
        if (job.currentSnapshot().qualification() != null)
            terms.add(new ApprovalClient.Term(
                    "Qualification", job.currentSnapshot().qualification()));
        if (!job.mustHaveSkills().isEmpty())
            terms.add(new ApprovalClient.Term("Must-have skills", String.join(", ", job.mustHaveSkills())));
        if (!job.currentSnapshot().goodToHaveSkills().isEmpty())
            terms.add(new ApprovalClient.Term(
                    "Good-to-have skills (optional)",
                    String.join(", ", job.currentSnapshot().goodToHaveSkills())));
        return List.copyOf(terms);
    }

    public JobResponse decision(String tenantId, String id, String decision, String actor, String reason) {
        return decision(tenantId, id, decision, actor, reason, null);
    }

    public JobResponse decision(
            String tenantId, String id, String decision, String actor, String reason, Integer expectedVersion) {
        return switch (decision.toUpperCase()) {
            case "APPROVED" -> approveAndPublish(tenantId, id, actor, expectedVersion);
            case "CHANGES_REQUESTED", "REJECTED" ->
                change(
                        tenantId,
                        id,
                        value -> {
                            requireDecisionVersion(value, expectedVersion);
                            value.requestChanges(actor, reason == null ? decision : reason);
                        },
                        "job.changes-requested.v1");
            default -> throw new IllegalArgumentException("Unsupported approval decision " + decision);
        };
    }

    private JobResponse approveAndPublish(String tenantId, String id, String actor, Integer expectedVersion) {
        var job = requireJob(tenantId, id);
        requireDecisionVersion(job, expectedVersion);
        // Approval callbacks can be redelivered. Never reopen a subsequently paused/closed job.
        if (job.approvedVersion() == job.currentVersion() && job.status() != JobStatus.PENDING_APPROVAL)
            return JobResponse.from(job);
        job.markApproved(actor);
        job.publish(actor);
        var saved = jobs.save(job);
        emit("job.approved.v1", saved);
        emit("job.published.v1", saved);
        recordAudit("JOB_PUBLISHED", saved, actor);
        return JobResponse.from(saved);
    }

    private static void requireDecisionVersion(Job job, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != job.currentVersion())
            throw new IllegalStateException("Approval belongs to a different job version");
    }

    public JobResponse publish(String tenantId, String id, String actor) {
        var job = requireJob(tenantId, id);
        if (job.status() == JobStatus.OPEN && job.approvedVersion() == job.currentVersion())
            return JobResponse.from(job);
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
        var selected = jobs.findAll(tenantId).stream()
                .filter(job -> status == null
                        || status.isBlank()
                        || job.status().name().equals(status))
                .toList();
        Map<String, com.openhire.jobservice.service.port.JobMetrics.Counts> counts;
        try {
            counts = metrics.counts(tenantId, selected.stream().map(Job::id).toList());
        } catch (org.springframework.web.client.RestClientException | IllegalStateException unavailable) {
            org.slf4j.LoggerFactory.getLogger(JobApplicationService.class)
                    .warn("Job application metrics unavailable; returning explicit unavailable values");
            counts = Map.of();
        }
        var availableCounts = counts;
        var data = selected.stream()
                .map(job -> JobSummaryResponse.from(job, availableCounts.get(job.id())))
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
        return descriptionGenerator.generate(new AiJobDraftRequest(
                        job.title(),
                        job.team(),
                        job.level(),
                        snapshot.mustHaveSkills(),
                        tone,
                        snapshot.qualification(),
                        snapshot.goodToHaveSkills(),
                        job.location(),
                        snapshot.classification() == null
                                ? null
                                : snapshot.classification().workMode())
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
        if (type.equals("job.approved.v1") || type.equals("job.published.v1")) {
            outbox.append(
                    "job.events.v1",
                    PublicationEvents.event(type, job.tenantId(), job.id(), job.currentVersion(), job.publishedAt()));
            return;
        }
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

    private JobContent content(
            String tenantId,
            CreateJobRequest request,
            List<Job.InterviewRound> interviewPlan,
            Job.JobVersion previous) {
        if (request.mustHaveSkills() == null || request.mustHaveSkills().isEmpty())
            throw new IllegalArgumentException("At least one must-have skill is required");
        if (request.qualification() != null && request.qualification().isBlank())
            throw new IllegalArgumentException("Qualification cannot be empty");
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
                interviewPlan,
                request.headcount() == null && previous != null ? previous.headcount() : request.headcount(),
                request.positionType() == null && previous != null ? previous.positionType() : request.positionType(),
                request.hiringReason() == null && previous != null ? previous.hiringReason() : request.hiringReason(),
                categories.resolve(
                        tenantId,
                        request.categoryId(),
                        request.workMode(),
                        previous == null ? null : previous.classification(),
                        previous == null),
                request.qualification() == null && previous != null
                        ? previous.qualification()
                        : request.qualification(),
                request.goodToHaveSkills() == null && previous != null
                        ? previous.goodToHaveSkills()
                        : request.goodToHaveSkills());
    }
}
