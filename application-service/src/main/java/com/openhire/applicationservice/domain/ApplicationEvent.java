package com.openhire.applicationservice.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Wire-compatible v1 envelope with string identifiers for portable Mongo storage. */
public record ApplicationEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String correlationId,
        Map<String, Object> payload) {
    public static ApplicationEvent create(
            String type, String tenant, String id, Instant at, Map<String, Object> payload) {
        return new ApplicationEvent(
                UUID.randomUUID().toString(), type, 1, at, tenant, "Application", id, id, Map.copyOf(payload));
    }
}
