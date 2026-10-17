package com.openhire.jobservice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobLifecycleTest {
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
