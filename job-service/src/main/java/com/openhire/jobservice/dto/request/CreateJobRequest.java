package com.openhire.jobservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

public record CreateJobRequest(
        @NotBlank String title,
        @NotBlank String team,
        @NotBlank String level,
        @NotBlank String location,
        SalaryBand salaryBand,
        @NotEmpty List<@NotBlank String> mustHaveSkills,
        List<KnockoutQuestion> knockoutQuestions,
        List<RubricCriterion> rubric,
        @NotBlank String description,
        String status) {

    public record SalaryBand(@NotBlank String currency, BigDecimal min, BigDecimal max) {}

    public record KnockoutQuestion(@NotBlank String id, @NotBlank String question, @NotBlank String requiredAnswer) {}

    public record RubricCriterion(@NotBlank String id, @NotBlank String label, int weight) {}
}
