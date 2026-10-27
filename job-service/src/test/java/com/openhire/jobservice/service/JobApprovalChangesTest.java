package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.domain.JobStatus;
import com.openhire.jobservice.service.port.ApprovalClient.FieldChange;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobApprovalChangesTest {
    @Test
    void comparesActualVersionedDescriptionAndIndividualRubricWeights() {
        var job = Job.create(
                "tenant-a", content("5 to 8 years building backend services.", 20, 15), JobStatus.DRAFT, "recruiter");
        job.edit(1, content("4 to 8 years building backend services.", 15, 20), "recruiter");
        var changes = JobApprovalChanges.between(job.versions().getFirst(), job.currentSnapshot());
        assertThat(changes)
                .containsExactly(
                        new FieldChange(
                                "Job description",
                                "5 to 8 years building backend services.",
                                "4 to 8 years building backend services."),
                        new FieldChange("Rubric: Payments domain", "Payments domain 20%", "Payments domain 15%"),
                        new FieldChange("Rubric: Ownership", "Ownership 15%", "Ownership 20%"));
        assertThat(JobApprovalChanges.between(job.currentSnapshot(), job.currentSnapshot()))
                .isEmpty();
    }

    private Job.JobContent content(String description, int payments, int ownership) {
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
                List.of(
                        new Job.RubricCriterion("payments", "Payments domain", payments),
                        new Job.RubricCriterion("ownership", "Ownership", ownership)),
                description,
                List.of());
    }
}
