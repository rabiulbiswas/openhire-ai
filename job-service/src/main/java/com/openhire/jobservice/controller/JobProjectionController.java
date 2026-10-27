package com.openhire.jobservice.controller;

import com.openhire.jobservice.service.JobApplicationService;
import com.openhire.platform.tenant.TenantContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class JobProjectionController {
    private final JobApplicationService jobs;
    private final com.openhire.jobservice.service.PublicCareerCatalog careers;

    public JobProjectionController(
            JobApplicationService jobs, com.openhire.jobservice.service.PublicCareerCatalog careers) {
        this.jobs = jobs;
        this.careers = careers;
    }

    @GetMapping("/public/jobs")
    Object publicJobs(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String location,
            @RequestParam(defaultValue = "") String categoryId) {
        return careers.list(tenantId, page, pageSize, keyword, location, categoryId);
    }

    @GetMapping("/public/jobs/{id}")
    Object publicJob(@RequestParam String tenantId, @PathVariable String id) {
        return careers.detail(tenantId, id);
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
