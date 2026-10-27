package com.openhire.jobservice.dto.response;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.domain.JobStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record JobResponse(
        String id,
        String code,
        String title,
        String team,
        String location,
        JobStatus status,
        int applicants,
        int shortlisted,
        int daysOpen,
        String hiringManager,
        Funnel funnel,
        long revision,
        int approvedVersion,
        String level,
        SalaryBand salaryBand,
        JobVersion currentVersion,
        List<HiringTeamMember> hiringTeam,
        List<Object> channels,
        List<Activity> activity,
        String categoryId,
        String categoryName,
        String workMode) {

    public static JobResponse from(Job job) {
        return new JobResponse(
                job.id(),
                job.code(),
                job.title(),
                job.team(),
                job.location(),
                job.status(),
                0,
                0,
                0,
                "",
                new Funnel(0, 0, 0, 0, 0, 0),
                job.revision() == null ? 0 : job.revision(),
                job.approvedVersion(),
                job.level(),
                new SalaryBand(job.currency(), job.salaryMin(), job.salaryMax()),
                new JobVersion(
                        job.currentVersion(),
                        versionStatus(job),
                        job.description(),
                        job.mustHaveSkills(),
                        job.knockoutQuestions().stream()
                                .map(value ->
                                        new KnockoutQuestion(value.id(), value.question(), value.requiredAnswer()))
                                .toList(),
                        job.rubric().stream()
                                .map(value -> new RubricCriterion(value.id(), value.label(), value.weight()))
                                .toList(),
                        job.currentSnapshot().interviewPlan().stream()
                                .map(value -> new InterviewRound(
                                        value.id(), value.name(), value.durationMin(), value.interviewerNames()))
                                .toList(),
                        job.currentSnapshot().headcount(),
                        job.currentSnapshot().positionType(),
                        job.currentSnapshot().hiringReason(),
                        job.currentSnapshot().qualification(),
                        job.currentSnapshot().goodToHaveSkills()),
                job.hiringTeam().stream()
                        .map(value -> new HiringTeamMember(value.userId(), value.name(), value.role()))
                        .toList(),
                List.of(),
                job.activity().stream()
                        .map(value -> new Activity(value.id(), value.at(), value.actor(), value.text()))
                        .toList(),
                job.currentSnapshot().classification() == null
                        ? null
                        : job.currentSnapshot().classification().categoryId(),
                job.currentSnapshot().classification() == null
                        ? null
                        : job.currentSnapshot().classification().categoryName(),
                job.currentSnapshot().classification() == null
                        ? null
                        : job.currentSnapshot().classification().workMode());
    }

    private static String versionStatus(Job job) {
        return switch (job.status()) {
            case OPEN, PAUSED, CLOSED -> "PUBLISHED";
            case DRAFT -> "DRAFT";
            case PENDING_APPROVAL -> "PENDING_APPROVAL";
        };
    }

    public record Funnel(int applied, int screened, int shortlisted, int interviewing, int offer, int hired) {}

    public record SalaryBand(String currency, BigDecimal min, BigDecimal max) {}

    public record KnockoutQuestion(String id, String question, String requiredAnswer) {}

    public record RubricCriterion(String id, String label, int weight) {}

    public record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames) {}

    public record HiringTeamMember(String userId, String name, String role) {}

    public record JobVersion(
            int version,
            String status,
            String description,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            List<InterviewRound> interviewPlan,
            Integer headcount,
            String positionType,
            String hiringReason,
            String qualification,
            List<String> goodToHaveSkills) {}

    public record Activity(String id, Instant at, String actor, String text) {}
}
