package com.openhire.jobservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openhire.jobservice.api.CreateJobRequest;
import com.openhire.jobservice.application.port.ApprovalClient;
import com.openhire.jobservice.application.port.IdempotencyStore;
import com.openhire.jobservice.application.port.JobDescriptionGenerator;
import com.openhire.jobservice.application.port.JobRepository;
import com.openhire.jobservice.application.port.OutboxWriter;
import com.openhire.jobservice.domain.Job;
import com.openhire.platform.audit.AuditRecorder;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private final JobApplicationService service =
            new JobApplicationService(jobs, outbox, idempotency, generator, approvals, audit);

    @Test
    void createsTenantScopedJobAndOutboxEvent() {
        var request = request();
        when(idempotency.findResourceId("tenant-a", "request-1")).thenReturn(Optional.empty());
        when(jobs.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create("tenant-a", "request-1", request);

        assertThat(response.title()).isEqualTo("Senior Java Backend Engineer");
        assertThat(response.currentVersion().version()).isEqualTo(1);
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
        return Job.create(
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
                "DRAFT");
    }
}
