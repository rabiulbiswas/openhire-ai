package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.dto.request.CreateJobRequest;
import com.openhire.jobservice.service.port.ApprovalClient;
import com.openhire.jobservice.service.port.IdempotencyStore;
import com.openhire.jobservice.service.port.JobDescriptionGenerator;
import com.openhire.jobservice.service.port.JobRepository;
import com.openhire.jobservice.service.port.OutboxWriter;
import com.openhire.platform.audit.AuditRecorder;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobApplicationServiceTest {
    private final JobRepository jobs = mock(JobRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final JobDescriptionGenerator generator = mock(JobDescriptionGenerator.class);
    private final ApprovalClient approvals = mock(ApprovalClient.class);
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
            "job-service",
            true);
    private final com.openhire.jobservice.service.port.JobMetrics metrics =
            mock(com.openhire.jobservice.service.port.JobMetrics.class);
    private final JobApplicationService service =
            new JobApplicationService(jobs, outbox, idempotency, generator, approvals, audit, metrics);

    @org.junit.jupiter.api.BeforeEach
    void membershipDirectory() {
        var categories = mock(JobCategories.class);
        when(categories.resolve(
                        any(),
                        org.mockito.ArgumentMatchers.nullable(String.class),
                        org.mockito.ArgumentMatchers.nullable(String.class),
                        org.mockito.ArgumentMatchers.nullable(Job.Classification.class),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(i -> i.getArgument(1) == null && !((Boolean) i.getArgument(4))
                        ? i.getArgument(3)
                        : new Job.Classification("category-it", "IT / Software", "HYBRID"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "categories", categories);
        var staff = mock(com.openhire.platform.security.TenantStaffDirectory.class);
        when(staff.require(any(), any(), any()))
                .thenAnswer(i -> new com.openhire.platform.security.TenantStaffDirectory.Member(
                        i.getArgument(1), "manager@example.test", "Manager", java.util.Set.of("HIRING_MANAGER")));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "staff", staff);
    }

    @Test
    void listUsesTenantBoundBatchMetricsAndAssignedManager() {
        var job = draftJob();
        job.updateHiringTeam(
                1, List.of(new Job.HiringTeamMember("manager-id", "Meera Nair", "HIRING_MANAGER")), "recruiter");
        when(jobs.findAll("tenant-a")).thenReturn(List.of(job));
        when(metrics.counts("tenant-a", List.of(job.id())))
                .thenReturn(Map.of(job.id(), new com.openhire.jobservice.service.port.JobMetrics.Counts(12, 3)));
        var row = service.list("tenant-a", null).data().getFirst();
        assertThat(row.applicants()).isEqualTo(12);
        assertThat(row.shortlisted()).isEqualTo(3);
        assertThat(row.metricsAvailable()).isTrue();
        assertThat(row.hiringManager()).isEqualTo("Meera Nair");
        assertThat(row.hiringManagerIds()).containsExactly("manager-id");
        verify(metrics).counts("tenant-a", List.of(job.id()));
    }

    @Test
    void missingManagerAndSelfAssignedManagerCannotSubmitOrPublishApprovalEvents() {
        var job = draftJob();
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        job.updateHiringTeam(1, List.of(), "recruiter");
        assertThatThrownBy(() -> service.submit("tenant-a", job.id(), "recruiter"))
                .hasMessageContaining("exactly one");
        job.updateHiringTeam(
                1, List.of(new Job.HiringTeamMember("recruiter", "Recruiter", "HIRING_MANAGER")), "recruiter");
        assertThatThrownBy(() -> service.submit("tenant-a", job.id(), "recruiter"))
                .hasMessageContaining("different hiring manager");
        verify(jobs, never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(approvals);
    }

    @Test
    void metricsOutageDoesNotPretendThereAreNoApplications() {
        var job = draftJob();
        when(jobs.findAll("tenant-a")).thenReturn(List.of(job));
        when(metrics.counts("tenant-a", List.of(job.id())))
                .thenThrow(new org.springframework.web.client.RestClientException("unavailable"));
        assertThat(service.list("tenant-a", null).data().getFirst().metricsAvailable())
                .isFalse();
    }

    @Test
    void staleApprovalCannotApproveAnotherJobVersion() {
        var job = draftJob();
        job.submit("recruiter");
        job.requestChanges("manager", "Revise");
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.update("tenant-a", job.id(), 1, "recruiter", request());
        job.submit("recruiter");
        assertThatThrownBy(() -> service.decision("tenant-a", job.id(), "APPROVED", "manager", null, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different job version");
        assertThat(job.approvedVersion()).isZero();
        service.decision("tenant-a", job.id(), "APPROVED", "manager", null, 2);
        assertThat(job.approvedVersion()).isEqualTo(2);
        assertThat(job.status()).isEqualTo(com.openhire.jobservice.domain.JobStatus.OPEN);
    }

    @Test
    void approvalPublishesOnceAndRedeliveryCannotReopenClosedJob() {
        var job = draftJob();
        job.submit("recruiter");
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.decision("tenant-a", job.id(), "APPROVED", "manager", null, 1);
        assertThat(job.status()).isEqualTo(com.openhire.jobservice.domain.JobStatus.OPEN);
        assertThat(job.publishedAt()).isNotNull();
        service.decision("tenant-a", job.id(), "APPROVED", "manager", null, 1);
        service.publish("tenant-a", job.id(), "recruiter");
        verify(jobs).save(job);
        var events = org.mockito.ArgumentCaptor.forClass(com.openhire.platform.event.DomainEventEnvelope.class);
        verify(outbox, org.mockito.Mockito.times(2)).append(eq("job.events.v1"), events.capture());
        assertThat(events.getAllValues())
                .extracting(com.openhire.platform.event.DomainEventEnvelope::eventType)
                .containsExactly("job.approved.v1", "job.published.v1");
        job.close("recruiter");
        service.decision("tenant-a", job.id(), "APPROVED", "manager", null, 1);
        assertThat(job.status()).isEqualTo(com.openhire.jobservice.domain.JobStatus.CLOSED);
    }

    @Test
    void resubmissionComparesAgainstTheLastSubmittedVersionNotTheLastDraftEdit() {
        var job = draftJob();
        job.submit("recruiter-7");
        job.requestChanges("manager", "Revise experience");
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.update("tenant-a", job.id(), 1, "recruiter-7", request());
        service.update("tenant-a", job.id(), 2, "recruiter-7", request());
        service.submit("tenant-a", job.id(), "recruiter-7");
        verify(approvals)
                .requestAssignedJobChangeApproval(
                        eq("tenant-a"),
                        eq("recruiter-7"),
                        eq(job.id()),
                        eq(job.title()),
                        eq(new ApprovalClient.Comparison(1, 3)),
                        org.mockito.ArgumentMatchers.argThat(diff -> diff.contains(new ApprovalClient.FieldChange(
                                "Job description",
                                "Build reliable services",
                                request().description()))),
                        eq("manager"));
        verify(approvals, never())
                .requestJobApproval(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void snapshotsEnteredRequisitionFieldsAndPreservesThemWhenLegacyEditsOmitThem() {
        var draft = request();
        var entered = new CreateJobRequest(
                draft.title(),
                draft.team(),
                draft.level(),
                draft.location(),
                draft.salaryBand(),
                draft.mustHaveSkills(),
                draft.knockoutQuestions(),
                draft.rubric(),
                draft.description(),
                "DRAFT",
                3,
                "NEW_POSITION",
                "Build a new payments team",
                null,
                null,
                null,
                "Degree or equivalent experience",
                List.of("Azure"));
        when(idempotency.findResourceId("tenant-a", "request-new")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create("tenant-a", "recruiter-7", "request-new", entered);
        var captor = org.mockito.ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(captor.capture());
        var job = captor.getValue();
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        service.update("tenant-a", job.id(), 1, "recruiter-7", draft);
        service.submit("tenant-a", job.id(), "recruiter-7");
        verify(approvals)
                .requestAssignedJobApproval(
                        eq("tenant-a"),
                        eq("recruiter-7"),
                        eq(response.id()),
                        eq(draft.title()),
                        eq(2),
                        org.mockito.ArgumentMatchers.argThat(terms -> terms.contains(
                                        new ApprovalClient.Term("Headcount", "3, new position"))
                                && terms.contains(new ApprovalClient.Term("Reason", "Build a new payments team"))),
                        eq("manager"));
        assertThat(job.versions().getFirst().headcount()).isEqualTo(3);
    }

    @Test
    void submitSendsSavedJobDetailsToApprovalWithoutInventingHeadcountOrReason() {
        var job = draftJob();
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.submit("tenant-a", job.id(), "recruiter-7");

        verify(approvals)
                .requestAssignedJobApproval(
                        "tenant-a",
                        "recruiter-7",
                        job.id(),
                        "Existing role",
                        1,
                        List.of(
                                new ApprovalClient.Term("Headcount", "Not provided"),
                                new ApprovalClient.Term("Salary band", "INR LPA 20 to 30"),
                                new ApprovalClient.Term("Location", "Pune"),
                                new ApprovalClient.Term("Job category", "Not specified"),
                                new ApprovalClient.Term("Work mode", "Not specified"),
                                new ApprovalClient.Term("Reason", "Not provided"),
                                new ApprovalClient.Term("Job description", "Build reliable services"),
                                new ApprovalClient.Term("Must-have skills", "Java")),
                        "manager");
    }

    @Test
    void directPendingCreationAlsoIncludesTheSubmittedDescription() {
        var draft = request();
        var pending = new CreateJobRequest(
                draft.title(),
                draft.team(),
                draft.level(),
                draft.location(),
                draft.salaryBand(),
                draft.mustHaveSkills(),
                draft.knockoutQuestions(),
                draft.rubric(),
                draft.description(),
                "PENDING_APPROVAL",
                null,
                null,
                null,
                "manager",
                null,
                null,
                "Degree or equivalent experience",
                List.of());
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create("tenant-a", "recruiter-7", "request-1", pending);
        verify(approvals)
                .requestAssignedJobApproval(
                        eq("tenant-a"),
                        eq("recruiter-7"),
                        eq(response.id()),
                        eq(draft.title()),
                        eq(1),
                        org.mockito.ArgumentMatchers.argThat(terms ->
                                terms.contains(new ApprovalClient.Term("Job description", draft.description()))),
                        eq("manager"));
    }

    @Test
    void createsTenantScopedJobAndOutboxEvent() {
        var request = request();
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create("tenant-a", "request-1", request);

        assertThat(response.title()).isEqualTo("Senior Java Backend Engineer");
        assertThat(response.currentVersion().version()).isEqualTo(1);
        assertThat(response.currentVersion().qualification()).isEqualTo("Degree or equivalent experience");
        assertThat(response.currentVersion().goodToHaveSkills()).containsExactly("Azure");
        verify(outbox).append(eq("job.events.v1"), any());
        verify(idempotency).remember(eq("tenant-a"), eq("request-1"), eq(response.id()), eq(Duration.ofHours(24)));
    }

    @Test
    void returnsOriginalJobForRepeatedIdempotencyKey() {
        var existing = Job.create(
                "tenant-a",
                "Existing role",
                "Engineering",
                "L5",
                "Pune",
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(30),
                "INR LPA",
                List.of("Java"),
                List.of(),
                List.of(),
                "Build reliable services",
                com.openhire.jobservice.domain.JobStatus.DRAFT);
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.of(existing.id()));
        when(jobs.findById("tenant-a", existing.id())).thenReturn(Optional.of(existing));

        var response = service.create("tenant-a", "request-1", request());

        assertThat(response.id()).isEqualTo(existing.id());
        verify(jobs, never()).save(any());
        verify(outbox, never()).append(any(), any());
    }

    @Test
    void neverReadsAJobOutsideTheResolvedTenant() {
        when(jobs.findById("tenant-b", "job-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("tenant-b", "job-1"))
                .isInstanceOf(com.openhire.platform.web.ResourceNotFoundException.class);

        verify(jobs).findById("tenant-b", "job-1");
        verify(jobs, never()).findById("tenant-a", "job-1");
    }

    @Test
    void auditsJobCreationWithActorAndIdsOnly() {
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create("tenant-a", "recruiter-7", "request-1", request());

        assertThat(audited).hasSize(1);
        var json = audited.getFirst();
        assertThat(json)
                .contains("\"action\":\"JOB_CREATED\"", "\"tenantId\":\"tenant-a\"")
                .contains("\"id\":\"recruiter-7\"", "\"type\":\"Job\"", "\"id\":\"" + response.id() + "\"")
                .contains("\"version\":1")
                .doesNotContain("Senior Java Backend Engineer")
                .doesNotContain("Build reliable payment services");
    }

    @Test
    void doesNotAuditAReplayedCreate() {
        var existing = draftJob();
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.of(existing.id()));
        when(jobs.findById("tenant-a", existing.id())).thenReturn(Optional.of(existing));

        service.create("tenant-a", "recruiter-7", "request-1", request());

        assertThat(audited).isEmpty();
    }

    @Test
    void auditsPublishAndClose() {
        var job = draftJob();
        job.submit("recruiter-7");
        job.markApproved("manager-1");
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.publish("tenant-a", job.id(), "recruiter-7");
        service.close("tenant-a", job.id(), "recruiter-8");

        assertThat(audited).hasSize(2);
        assertThat(audited.get(0)).contains("\"action\":\"JOB_PUBLISHED\"", "\"id\":\"recruiter-7\"");
        assertThat(audited.get(1)).contains("\"action\":\"JOB_CLOSED\"", "\"id\":\"recruiter-8\"");
        assertThat(audited.get(1)).contains("\"id\":\"" + job.id() + "\"");
    }

    @Test
    void doesNotAuditAnActionThatFailed() {
        var job = draftJob();
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.publish("tenant-a", job.id(), "recruiter-7"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(audited).isEmpty();
    }

    @Test
    void anAuditFailureNeverBreaksTheBusinessAction() {
        auditBroken = true;
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create("tenant-a", "recruiter-7", "request-1", request());

        assertThat(response.title()).isEqualTo("Senior Java Backend Engineer");
        assertThat(audited).isEmpty();
    }

    private Job draftJob() {
        var job = Job.create(
                "tenant-a",
                "Existing role",
                "Engineering",
                "L5",
                "Pune",
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(30),
                "INR LPA",
                List.of("Java"),
                List.of(),
                List.of(),
                "Build reliable services",
                com.openhire.jobservice.domain.JobStatus.DRAFT);
        job.updateHiringTeam(1, List.of(new Job.HiringTeamMember("manager", "Manager", "HIRING_MANAGER")), "recruiter");
        return job;
    }

    @Test
    void newJobsRequireQualificationBeforeAnyWrite() {
        var original = request();
        var missing = new CreateJobRequest(
                original.title(),
                original.team(),
                original.level(),
                original.location(),
                original.salaryBand(),
                original.mustHaveSkills(),
                original.knockoutQuestions(),
                original.rubric(),
                original.description(),
                "DRAFT");
        assertThatThrownBy(() -> service.create("tenant-a", "recruiter", null, missing))
                .hasMessageContaining("Qualification is required");
        verify(jobs, never()).save(any());
    }

    @Test
    void legacyEditsKeepExistingRequirementsWhenOmitted() {
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.create("tenant-a", "recruiter", null, request());
        var capture = org.mockito.ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(capture.capture());
        var job = capture.getValue();
        when(jobs.findById("tenant-a", job.id())).thenReturn(Optional.of(job));
        var r = request();
        var legacy = new CreateJobRequest(
                r.title(),
                r.team(),
                r.level(),
                r.location(),
                r.salaryBand(),
                r.mustHaveSkills(),
                r.knockoutQuestions(),
                r.rubric(),
                r.description(),
                "DRAFT");
        var changed = service.update("tenant-a", job.id(), 1, "recruiter", legacy);
        assertThat(changed.currentVersion().qualification()).isEqualTo(r.qualification());
        assertThat(changed.currentVersion().goodToHaveSkills()).containsExactly("Azure");
        job.updateInterviewPlan(2, List.of(new Job.InterviewRound("r1", "Technical", 60, List.of())), "recruiter");
        assertThat(job.currentSnapshot().qualification()).isEqualTo(r.qualification());
        assertThat(job.currentSnapshot().goodToHaveSkills()).containsExactly("Azure");
    }

    private CreateJobRequest request() {
        return new CreateJobRequest(
                "Senior Java Backend Engineer",
                "Engineering",
                "L5",
                "Bengaluru",
                new CreateJobRequest.SalaryBand("INR LPA", BigDecimal.valueOf(38), BigDecimal.valueOf(48)),
                List.of("Java 21", "Spring Boot", "Kafka"),
                List.of(),
                List.of(),
                "Build reliable payment services",
                "DRAFT",
                null,
                null,
                null,
                "manager",
                null,
                null,
                "Degree or equivalent experience",
                List.of("Azure"));
    }
}
