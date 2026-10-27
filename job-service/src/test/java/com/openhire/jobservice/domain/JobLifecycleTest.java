package com.openhire.jobservice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobLifecycleTest {
    @Test
    void classificationIsVersionedAndSurvivesInterviewPlanChanges() {
        var base = content("Description");
        var classification = new Job.Classification("category-it", "IT / Software", "HYBRID");
        var content = new Job.JobContent(
                base.title(),
                base.team(),
                base.level(),
                base.location(),
                base.salaryMin(),
                base.salaryMax(),
                base.currency(),
                base.mustHaveSkills(),
                base.knockoutQuestions(),
                base.rubric(),
                base.description(),
                base.interviewPlan(),
                null,
                null,
                null,
                classification);
        var job = Job.create("tenant", content, JobStatus.DRAFT, "recruiter");
        job.updateInterviewPlan(1, List.of(), "recruiter");
        assertThat(job.currentSnapshot().classification()).isEqualTo(classification);
        assertThat(job.versions().getFirst().classification()).isEqualTo(classification);
        assertThat(com.openhire.jobservice.dto.response.JobResponse.from(job).categoryName())
                .isEqualTo("IT / Software");
    }

    @Test
    void incompleteRequisitionCanBeDraftedButCannotBeSubmitted() {
        var base = content("Description");
        var incomplete = new Job.JobContent(
                base.title(),
                base.team(),
                base.level(),
                base.location(),
                base.salaryMin(),
                base.salaryMax(),
                base.currency(),
                base.mustHaveSkills(),
                base.knockoutQuestions(),
                base.rubric(),
                base.description(),
                base.interviewPlan(),
                1,
                "NEW_POSITION",
                "  ");
        var job = Job.create("tenant-a", incomplete, JobStatus.DRAFT, "recruiter");
        assertThat(job.status()).isEqualTo(JobStatus.DRAFT);
        assertThatThrownBy(() -> job.submit("recruiter"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hiring reason");
        assertThat(job.status()).isEqualTo(JobStatus.DRAFT);
    }

    @Test
    void requisitionDetailsAreVersionedAndSurviveInterviewPlanUpdates() {
        var base = content("Description");
        var first = new Job.JobContent(
                base.title(),
                base.team(),
                base.level(),
                base.location(),
                base.salaryMin(),
                base.salaryMax(),
                base.currency(),
                base.mustHaveSkills(),
                base.knockoutQuestions(),
                base.rubric(),
                base.description(),
                base.interviewPlan(),
                2,
                "REPLACEMENT",
                "  Replace departing engineers  ");
        var job = Job.create("tenant-a", first, JobStatus.DRAFT, "recruiter");
        job.updateInterviewPlan(
                1, List.of(new Job.InterviewRound("technical", "Technical", 45, List.of())), "recruiter");
        assertThat(job.currentSnapshot().headcount()).isEqualTo(2);
        assertThat(job.currentSnapshot().positionType()).isEqualTo("REPLACEMENT");
        assertThat(job.currentSnapshot().hiringReason()).isEqualTo("Replace departing engineers");
        assertThat(job.versions().getFirst().headcount()).isEqualTo(2);
        var response = com.openhire.jobservice.dto.response.JobResponse.from(job);
        assertThat(response.currentVersion().headcount()).isEqualTo(2);
        assertThat(response.currentVersion().hiringReason()).isEqualTo("Replace departing engineers");
    }

    @Test
    void rejectsInvalidRequisitionFieldsAndAcceptsLegacyRequests() {
        var request = new com.openhire.jobservice.dto.request.CreateJobRequest(
                "Engineer",
                "Platform",
                "L4",
                "Remote",
                null,
                List.of("Java"),
                List.of(),
                List.of(),
                "Description",
                "DRAFT",
                0,
                "UNKNOWN",
                "x".repeat(4001));
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("headcount", "positionType", "hiringReason");
        }
        var legacy = Job.create("tenant-a", content("Description"), JobStatus.DRAFT, "recruiter");
        assertThat(legacy.currentSnapshot().headcount()).isNull();
        assertThat(legacy.currentSnapshot().hiringReason()).isNull();
    }

    @Test
    void createEditSubmitApprovePublishKeepsImmutableSnapshots() {
        var first = content("First description");
        var job = Job.create("tenant-a", first, JobStatus.DRAFT, "recruiter");

        job.edit(1, content("Second description"), "recruiter");
        job.submit("recruiter");
        job.markApproved("manager");
        job.publish("recruiter");

        assertThat(job.status()).isEqualTo(JobStatus.OPEN);
        assertThat(job.approvedVersion()).isEqualTo(2);
        assertThat(job.versions())
                .extracting(Job.JobVersion::description)
                .containsExactly("First description", "Second description");
    }

    @Test
    void staleVersionAndPublishBeforeApprovalAreRejected() {
        var job = Job.create("tenant-a", content("Description"), JobStatus.DRAFT, "recruiter");

        assertThatThrownBy(() -> job.edit(2, content("Change"), "recruiter")).isInstanceOf(IllegalStateException.class);
        job.submit("recruiter");
        assertThatThrownBy(() -> job.publish("recruiter")).isInstanceOf(IllegalStateException.class);
    }

    private Job.JobContent content(String description) {
        return new Job.JobContent(
                "Engineer",
                "Platform",
                "L4",
                "Remote",
                BigDecimal.TEN,
                BigDecimal.valueOf(20),
                "INR",
                List.of("Java"),
                List.of(),
                List.of(),
                description,
                List.of());
    }
}
