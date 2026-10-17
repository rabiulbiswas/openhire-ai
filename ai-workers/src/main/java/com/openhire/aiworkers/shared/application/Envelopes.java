package com.openhire.aiworkers.shared.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class Envelopes {
    private Envelopes() {}

    public static Map<String, Object> event(
            String type, String tenantId, String aggregateType, String aggregateId, Map<String, Object> payload) {
        var event = new LinkedHashMap<String, Object>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("producer", "ai-workers");
        event.put("eventType", type);
        event.put("eventVersion", 1);
        event.put("occurredAt", Instant.now().toString());
        event.put("tenantId", tenantId);
        event.put("aggregateType", aggregateType);
        event.put("aggregateId", aggregateId);
        event.put("correlationId", aggregateId);
        event.put("payload", payload);
        return event;
    }
}
