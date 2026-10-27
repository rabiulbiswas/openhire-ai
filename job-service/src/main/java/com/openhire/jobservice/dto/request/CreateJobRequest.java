package com.openhire.jobservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record CreateJobRequest(
        @NotBlank String title,
        @NotBlank String team,
        @NotBlank String level,
        @NotBlank String location,
        SalaryBand salaryBand,
        @NotEmpty @Size(max = 15) List<@NotBlank @Size(max = 60) String> mustHaveSkills,
        List<KnockoutQuestion> knockoutQuestions,
        List<RubricCriterion> rubric,
        @NotBlank String description,
        String status,
        @Min(1) Integer headcount,
        @Pattern(regexp = "NEW_POSITION|REPLACEMENT") String positionType,
        @Size(max = 4000) String hiringReason,
        String hiringManagerId,
        @Size(max = 100) String categoryId,
        @Pattern(regexp = "REMOTE|ONSITE|HYBRID") String workMode,
        @Size(max = 1000) String qualification,
        @Size(max = 15) List<@NotBlank @Size(max = 60) String> goodToHaveSkills) {

    public CreateJobRequest(
            String title,
            String team,
            String level,
            String location,
            SalaryBand salaryBand,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            String status,
            Integer headcount,
            String positionType,
            String hiringReason,
            String hiringManagerId,
            String categoryId,
            String workMode) {
        this(
                title,
                team,
                level,
                location,
                salaryBand,
                mustHaveSkills,
                knockoutQuestions,
                rubric,
                description,
                status,
                headcount,
                positionType,
                hiringReason,
                hiringManagerId,
                categoryId,
                workMode,
                null,
                null);
    }

    public CreateJobRequest(
            String title,
            String team,
            String level,
            String location,
            SalaryBand salaryBand,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            String status,
            Integer headcount,
            String positionType,
            String hiringReason,
            String hiringManagerId) {
        this(
                title,
                team,
                level,
                location,
                salaryBand,
                mustHaveSkills,
                knockoutQuestions,
                rubric,
                description,
                status,
                headcount,
                positionType,
                hiringReason,
                hiringManagerId,
                null,
                null);
    }

    public CreateJobRequest(
            String title,
            String team,
            String level,
            String location,
            SalaryBand salaryBand,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            String status,
            Integer headcount,
            String positionType,
            String hiringReason) {
        this(
                title,
                team,
                level,
                location,
                salaryBand,
                mustHaveSkills,
                knockoutQuestions,
                rubric,
                description,
                status,
                headcount,
                positionType,
                hiringReason,
                null);
    }

    /** Older integrations can omit the additive requisition fields. */
    public CreateJobRequest(
            String title,
            String team,
            String level,
            String location,
            SalaryBand salaryBand,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            String status) {
        this(
                title,
                team,
                level,
                location,
                salaryBand,
                mustHaveSkills,
                knockoutQuestions,
                rubric,
                description,
                status,
                null,
                null,
                null,
                null);
    }

    public record SalaryBand(@NotBlank String currency, BigDecimal min, BigDecimal max) {}

    public record KnockoutQuestion(@NotBlank String id, @NotBlank String question, @NotBlank String requiredAnswer) {}

    public record RubricCriterion(@NotBlank String id, @NotBlank String label, int weight) {}
}
