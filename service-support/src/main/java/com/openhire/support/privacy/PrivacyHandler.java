package com.openhire.support.privacy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Turns a compliance request event into acknowledgements, one per participant. Pure logic; no Kafka. */
public final class PrivacyHandler {
    public static final String REQUEST_TOPIC = "compliance.events.v1";
    public static final String ACK_TOPIC = "privacy.acks.v1";
    public static final String ERASURE_REQUESTED = "compliance.erasure.requested.v1";
    public static final String EXPORT_REQUESTED = "compliance.export.requested.v1";

    public record Ack(String key, String json) {}

    private final ObjectMapper json = new ObjectMapper();
    private final List<PrivacyParticipant> participants;
    private final String producer;

    public PrivacyHandler(List<PrivacyParticipant> participants, String producer) {
        this.participants = List.copyOf(participants);
        this.producer = producer;
    }

    /** Acknowledgements for the event, empty when it is not a request this service answers. */
    public List<Ack> handle(String message) {
        JsonNode event = json.readTree(message);
        var type = text(event, "eventType");
        boolean erasure = ERASURE_REQUESTED.equals(type);
        if (!erasure && !EXPORT_REQUESTED.equals(type)) return List.of();
        var tenant = text(event, "tenantId");
        var payload = event.get("payload");
        var requestId = text(payload, "requestId");
        var candidateId = text(payload, "candidateId");
        if (tenant.isEmpty() || requestId.isEmpty() || candidateId.isEmpty())
            throw new IllegalArgumentException("A privacy request needs a tenant, a request id and a candidate id");
        var acks = new ArrayList<Ack>();
        for (var participant : participants) {
            var body = new LinkedHashMap<String, Object>();
            body.put("requestId", requestId);
            body.put("service", participant.service());
            body.put("candidateId", candidateId);
            String ackType;
            if (erasure) {
                var outcome = participant.erase(tenant, requestId, candidateId);
                ackType = "privacy.erasure.completed.v1";
                body.put("status", outcome.status().name());
                body.put("counts", outcome.counts());
                if (outcome.note() != null) body.put("note", outcome.note());
            } else {
                ackType = "privacy.export.completed.v1";
                body.put("status", "EXPORTED");
                body.put("section", participant.export(tenant, candidateId));
            }
            acks.add(new Ack(requestId, json.writeValueAsString(envelope(ackType, tenant, requestId, body))));
        }
        return acks;
    }

    private Map<String, Object> envelope(String type, String tenant, String requestId, Map<String, Object> payload) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("producer", producer);
        envelope.put("eventType", type);
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("tenantId", tenant);
        envelope.put("aggregateType", "PrivacyRequest");
        envelope.put("aggregateId", requestId);
        envelope.put("correlationId", requestId);
        envelope.put("payload", payload);
        return envelope;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : "";
    }
}
