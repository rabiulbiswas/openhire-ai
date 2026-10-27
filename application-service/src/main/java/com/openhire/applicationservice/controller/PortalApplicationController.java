package com.openhire.applicationservice.controller;

import com.openhire.applicationservice.repository.mongo.JobMetricsRepository;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import java.util.Map;
import org.bson.Document;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PortalApplicationController {
    private final Access access;
    private final SubmissionService submissions;
    private final JobMetricsRepository metrics;

    PortalApplicationController(Access access, SubmissionService submissions, JobMetricsRepository metrics) {
        this.access = access;
        this.submissions = submissions;
        this.metrics = metrics;
    }

    // Internal only: no public generic webhook. Vendor adapters must verify signatures and map remote job IDs.
    // Existing consent validation and job-open checks apply; never invent candidate consent during imports.
    @PostMapping("/internal/portal-applications/{provider}")
    Object intake(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable String provider,
            @RequestBody Document input)
            throws Exception {
        access.internal(secret);
        if (tenant.isBlank() || tenant.length() > 100) throw new IllegalArgumentException("Tenant required");
        return Map.of(
                "id",
                submissions.submitPortal(tenant, provider, key, input, null).getString("_id"));
    }

    @GetMapping("/jobs/{id}/application-sources")
    Object sources(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        String tenant = access.tenant(auth);
        submissions.job(tenant, id);
        return metrics.sources(tenant, id);
    }
}
