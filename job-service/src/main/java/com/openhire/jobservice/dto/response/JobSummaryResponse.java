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
        JobResponse.Funnel funnel) {

    public static JobSummaryResponse from(Job job) {
        return new JobSummaryResponse(
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
                new JobResponse.Funnel(0, 0, 0, 0, 0, 0));
    }
}
