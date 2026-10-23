package com.openhire.aigateway.controller;

import com.openhire.aigateway.service.port.LogRetention;
import com.openhire.support.Access;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/ai/log-retention")
class LogRetentionController {
    private final LogRetention logs;
    private final Access access;
    private final Clock clock;

    LogRetentionController(LogRetention logs, Access access, Clock clock) {
        this.logs = logs;
        this.access = access;
        this.clock = clock;
    }

    record Input(String tenantId, String cutoff, boolean dryRun, int limit) {}

    @GetMapping("/tenants")
    Map<String, Object> tenants(@RequestHeader("X-Internal-Service-Secret") String secret) {
        access.internal(secret);
        return Map.of("tenants", logs.tenants());
    }

    @PostMapping
    Map<String, Object> run(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Input input) {
        access.internal(secret);
        Instant cutoff;
        try {
            cutoff = Instant.parse(input.cutoff());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cutoff must be an ISO instant", invalid);
        }
        // Defence in depth: a live request cannot delete logs newer than the minimum one-month policy.
        if (!input.dryRun()
                && cutoff.isAfter(
                        clock.instant().atZone(ZoneOffset.UTC).minusMonths(1).toInstant()))
            throw new IllegalArgumentException("Live retention cutoff must be at least one month old");
        long count = logs.purge(input.tenantId(), cutoff, input.dryRun(), input.limit());
        return Map.of(
                "key",
                "ai.request-logs",
                "status",
                "SUCCESS",
                "dryRun",
                input.dryRun(),
                "processed",
                input.dryRun() ? 0L : count,
                "eligibleInBatch",
                count,
                "limit",
                input.limit());
    }
}
