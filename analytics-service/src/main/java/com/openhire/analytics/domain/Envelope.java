package com.openhire.analytics.domain;

import java.time.Instant;
import java.util.Map;

/** The parts of a domain event envelope the projections read; every field except the type may be absent. */
public record Envelope(
        String eventId,
        String eventType,
        String tenantId,
        String aggregateId,
        Instant occurredAt,
        Map<String, Object> payload) {
    public Envelope {
        eventId = eventId == null ? "" : eventId;
        eventType = eventType == null ? "" : eventType;
        tenantId = tenantId == null ? "" : tenantId;
        aggregateId = aggregateId == null ? "" : aggregateId;
        payload = payload == null ? Map.of() : payload;
    }

    public String text(String key) {
        var value = payload.get(key);
        return value == null ? "" : value.toString().trim();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        return payload.get(key) instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
    }

    public double number(String key) {
        return payload.get(key) instanceof Number value ? value.doubleValue() : 0;
    }
}
