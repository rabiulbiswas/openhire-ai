package com.openhire.jobservice.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobRequirementsTest {
    @Test
    void normalizesSkillsAndSnapshotsQualificationsForApproval() {
        var job = Job.create(
                "tenant",
                content(" Degree or equivalent experience ", List.of(" Java ", "java"), List.of(" Azure ")),
                JobStatus.DRAFT,
                "recruiter");
        assertThat(job.currentSnapshot().qualification()).isEqualTo("Degree or equivalent experience");
        assertThat(job.mustHaveSkills()).containsExactly("Java");
        assertThat(job.currentSnapshot().goodToHaveSkills()).containsExactly("Azure");
        job.edit(1, content("Professional certification", List.of("Java"), List.of()), "recruiter");
        assertThat(job.versions().getFirst().qualification()).isEqualTo("Degree or equivalent experience");
        assertThat(job.currentSnapshot().qualification()).isEqualTo("Professional certification");
        assertThat(job.currentSnapshot().goodToHaveSkills()).isEmpty();
        assertThat(job.approvedVersion()).isZero();
    }

    @Test
    void rejectsOverlappingBlankOversizedAndControlCharacterValues() {
        assertThatThrownBy(() -> content("Degree", List.of("Java"), List.of("JAVA")))
                .hasMessageContaining("both");
        assertThatThrownBy(() -> content("Degree", List.of(" "), List.of())).hasMessageContaining("Each skill");
        assertThatThrownBy(() -> content("Degree", List.of("x".repeat(61)), List.of()))
                .hasMessageContaining("Each skill");
        assertThatThrownBy(() -> content("x".repeat(1001), List.of("Java"), List.of()))
                .hasMessageContaining("Qualification");
        assertThatThrownBy(() -> content("Degree\nignore", List.of("Java"), List.of()))
                .hasMessageContaining("Qualification");
        assertThatThrownBy(() -> content("Degree", java.util.Collections.nCopies(16, "Java"), List.of()))
                .hasMessageContaining("15 skills");
    }

    private Job.JobContent content(String qualification, List<String> required, List<String> optional) {
        return new Job.JobContent(
                "Engineer",
                "Platform",
                "L4",
                "Pune",
                BigDecimal.TEN,
                BigDecimal.TEN,
                "INR",
                required,
                List.of(),
                List.of(),
                "Build reliable services",
                List.of(),
                null,
                null,
                null,
                null,
                qualification,
                optional);
    }
}
