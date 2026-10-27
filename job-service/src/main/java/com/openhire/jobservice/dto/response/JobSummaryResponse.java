package com.openhire.jobservice.dto.response;

import com.openhire.jobservice.domain.Job;
import com.openhire.jobservice.domain.JobStatus;

public record JobSummaryResponse(
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
        JobResponse.Funnel funnel,
        boolean metricsAvailable,
        java.util.List<String> hiringManagerIds,
        String categoryId,
        String categoryName,
        String workMode) {

    public static JobSummaryResponse from(Job job) {
        return from(job, null);
    }

    public static JobSummaryResponse from(Job job, com.openhire.jobservice.service.port.JobMetrics.Counts counts) {
        var managers = job.hiringTeam().stream()
                .filter(member -> member.role() != null
                        && member.role().trim().replace(' ', '_').equalsIgnoreCase("HIRING_MANAGER"))
                .toList();
        return new JobSummaryResponse(
                job.id(),
                job.code(),
                job.title(),
                job.team(),
                job.location(),
                job.status(),
                counts == null ? 0 : counts.applicants(),
                counts == null ? 0 : counts.shortlisted(),
                job.publishedAt() == null
                        ? 0
                        : (int) Math.max(
                                0,
                                java.time.Duration.between(job.publishedAt(), java.time.Instant.now())
                                        .toDays()),
                managers.stream()
                        .map(Job.HiringTeamMember::name)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining(", ")),
                new JobResponse.Funnel(0, 0, counts == null ? 0 : counts.shortlisted(), 0, 0, 0),
                counts != null,
                managers.stream()
                        .map(Job.HiringTeamMember::userId)
                        .filter(java.util.Objects::nonNull)
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
}
