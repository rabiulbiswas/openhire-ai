package com.openhire.employee.adapter.in.kafka;

import com.openhire.employee.application.HireSagaService;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inputs of the hire saga. Offer acceptance starts it, the application's move to HIRED completes the wait, and the
 * integration hub reports the optional HRIS hand-off. Every handler is idempotent under redelivery.
 */
@Component
class EmployeeEventsListener {
    private final HireSagaService sagas;
    private final ObjectMapper json;

    EmployeeEventsListener(HireSagaService sagas, ObjectMapper json) {
        this.sagas = sagas;
        this.json = json;
    }

    @KafkaListener(topics = "offer.events.v1", groupId = "employee-offer-events-v1")
    void onOfferEvent(String message) {
        var event = parse(message);
        if (!"offer.accepted.v1".equals(event.get("eventType"))) return;
        var payload = map(event.get("payload"));
        var terms = map(payload.get("terms"));
        sagas.start(new OfferAccepted(
                required(event, "tenantId"),
                required(payload, "offerId"),
                required(payload, "applicationId"),
                text(payload, "candidateId"),
                text(payload, "candidateName"),
                text(payload, "jobId"),
                text(payload, "jobTitle"),
                text(payload, "ownerId"),
                text(terms, "joiningDate"),
                text(terms, "designation"),
                text(terms, "location"),
                text(terms, "reportsTo")));
    }

    @KafkaListener(topics = "application.events.v1", groupId = "employee-application-events-v1")
    void onApplicationEvent(String message) {
        var event = parse(message);
        if (!"ApplicationStageChanged".equals(event.get("eventType"))) return;
        if (!"HIRED".equals(map(event.get("payload")).get("to"))) return;
        sagas.applicationHired(required(event, "tenantId"), required(event, "aggregateId"));
    }

    @KafkaListener(topics = "integration.events.v1", groupId = "employee-integration-events-v1")
    void onIntegrationEvent(String message) {
        var event = parse(message);
        var type = text(event, "eventType");
        if (!type.equals("integration.hris.synced.v1") && !type.equals("integration.hris.failed.v1")) return;
        var payload = map(event.get("payload"));
        var employeeId = text(payload, "employeeId");
        if (employeeId.isBlank()) employeeId = required(event, "aggregateId");
        sagas.hrisOutcome(required(event, "tenantId"), employeeId, type.equals("integration.hris.synced.v1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String message) {
        return json.readValue(message, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Map<String, Object> source, String key) {
        var value = source.get(key);
        return value == null ? "" : value.toString();
    }

    private static String required(Map<String, Object> source, String key) {
        var value = text(source, key).trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
