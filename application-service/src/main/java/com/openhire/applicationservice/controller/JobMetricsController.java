package com.openhire.applicationservice.controller;

import com.openhire.applicationservice.repository.mongo.JobMetricsRepository;
import com.openhire.support.Access;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/jobs/metrics")
public class JobMetricsController {
    private final Access access;
    private final JobMetricsRepository metrics;

    public JobMetricsController(Access access, JobMetricsRepository metrics) {
        this.access = access;
        this.metrics = metrics;
    }

    @PostMapping
    public List<JobMetricsRepository.Counts> counts(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody List<String> jobIds) {
        access.internal(secret);
        if (tenantId.isBlank() || jobIds.size() > 200 || jobIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant and up to 200 job ids required");
        return metrics.counts(tenantId, jobIds);
    }
}
