package com.openhire.jobservice.controller;

import com.openhire.jobservice.service.JobApplicationService;
import com.openhire.platform.tenant.TenantContext;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class JobProjectionController {
    private final JobApplicationService jobs;

    public JobProjectionController(JobApplicationService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/public/jobs")
    Object publicJobs(@RequestParam String tenantId) {
        return jobs.list(tenantId, "OPEN");
    }

    @GetMapping("/public/jobs/{id}")
    Object publicJob(@RequestParam String tenantId, @PathVariable String id) {
        var job = jobs.get(tenantId, id);
        if (job.status() != com.openhire.jobservice.domain.JobStatus.OPEN)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of(
                "id",
                job.id(),
                "title",
                job.title(),
                "location",
                job.location(),
                "description",
                job.currentVersion().description(),
                "mustHaveSkills",
                job.currentVersion().mustHaveSkills(),
                "questions",
                job.currentVersion().knockoutQuestions().stream()
                        .map(question -> Map.of("id", question.id(), "question", question.question()))
                        .toList());
    }

    @GetMapping("/internal-jobs")
    Object internalJobs() {
        return jobs.list(TenantContext.requiredTenantId(), "OPEN");
    }

    @GetMapping("/me/inbox")
    Map<String, Object> today() {
        var openJobs = jobs.list(TenantContext.requiredTenantId(), "OPEN").data();
        var pending =
                jobs.list(TenantContext.requiredTenantId(), "PENDING_APPROVAL").data();
        var items = pending.stream()
                .map(job -> Map.of(
                        "id",
                        "job:" + job.id(),
                        "kind",
                        "APPROVE",
                        "title",
                        job.title(),
                        "detail",
                        "Job approval is pending",
                        "actionLabel",
                        "Review",
                        "link",
                        Map.of("route", "approvals")))
                .toList();
        return Map.of(
                "greetingName",
                "Recruiter",
                "items",
                items,
                "aiActivity",
                Map.of("screened", 0, "parsedCleanlyPct", 0, "medianApplyToScoreSec", 0, "agreementPct", 0),
                "openJobs",
                openJobs);
    }
}
