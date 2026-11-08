package com.openhire.aigateway.controller;

import com.openhire.aigateway.service.InterviewAiTask;
import com.openhire.support.Access;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
class InterviewAiController {
    private final Access access;
    private final InterviewAiTask task;

    InterviewAiController(Access access, InterviewAiTask task) {
        this.access = access;
        this.task = task;
    }

    @PostMapping("/api/v1/internal/ai/interview-assist")
    Map<String, Object> generate(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestBody InterviewAiTask.Request request) {
        access.internal(secret);
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("X-Tenant-Id is required");
        return task.generate(tenant, request);
    }
}
