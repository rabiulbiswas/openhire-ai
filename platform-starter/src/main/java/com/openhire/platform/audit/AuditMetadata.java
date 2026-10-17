package com.openhire.platform.audit;

import java.time.Instant;

public record AuditMetadata(String actorId, String traceId, Instant occurredAt) {
    public AuditMetadata {
        if (actorId == null || actorId.isBlank()) actorId = "system";
        if (traceId == null || traceId.isBlank()) traceId = "unknown";
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public static AuditMetadata now(String actorId, String traceId) {
        return new AuditMetadata(actorId, traceId, Instant.now());
    }
}
