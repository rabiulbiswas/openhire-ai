package com.openhire.platform.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String tenantId,
        String aggregateType,
        String aggregateId,
        String correlationId,
        Map<String, Object> payload) {

    public static DomainEventEnvelope create(
            String eventType,
            String tenantId,
            String aggregateType,
            String aggregateId,
            String correlationId,
            Map<String, Object> payload) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                eventType,
                1,
                Instant.now(),
                tenantId,
                aggregateType,
                aggregateId,
                correlationId,
                Map.copyOf(payload));
    }
}
