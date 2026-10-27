package com.openhire.jobservice.service;

import com.openhire.platform.event.DomainEventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class PublicationEvents {
    private PublicationEvents() {}

    public static DomainEventEnvelope event(String type, String tenant, String id, int version, Instant at) {
        var key = UUID.nameUUIDFromBytes(
                (tenant + ":" + id + ":" + version + ":" + type).getBytes(StandardCharsets.UTF_8));
        return new DomainEventEnvelope(
                key,
                type,
                1,
                at,
                tenant,
                "Job",
                id,
                key.toString(),
                Map.of("jobId", id, "status", "OPEN", "version", version));
    }
}
