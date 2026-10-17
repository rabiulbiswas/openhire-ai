package com.openhire.communication.adapter.in.kafka;

import com.openhire.communication.application.CommunicationService;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Observer of application, interview, offer, employee and approval events; trigger rules decide who hears about each one. */
@Component
class LifecycleEventsListener {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleEventsListener.class);
    private final CommunicationService communication;
    private final ObjectMapper json;

    LifecycleEventsListener(CommunicationService communication, ObjectMapper json) {
        this.communication = communication;
        this.json = json;
    }

    @KafkaListener(topics = "application.events.v1", groupId = "communication-application-events-v1")
    void onApplicationEvent(String message) {
        handle(message, true);
    }

    @KafkaListener(topics = "interview.events.v1", groupId = "communication-interview-events-v1")
    void onInterviewEvent(String message) {
        handle(message, false);
    }

    @KafkaListener(topics = "offer.events.v1", groupId = "communication-offer-events-v1")
    void onOfferEvent(String message) {
        handle(message, false);
    }

    @KafkaListener(topics = "employee.events.v1", groupId = "communication-employee-events-v1")
    void onEmployeeEvent(String message) {
        handle(message, false);
    }

    @KafkaListener(topics = "approval.events.v1", groupId = "communication-approval-events-v1")
    @SuppressWarnings("unchecked")
    void onApprovalEvent(String message) {
        Map<String, Object> event = json.readValue(message, Map.class);
        var payload = event.get("payload") instanceof Map<?, ?> value
                ? (Map<String, Object>) value
                : Map.<String, Object>of();
        communication.handleApprovalEvent(
                String.valueOf(event.get("eventId")),
                String.valueOf(event.get("eventType")),
                (String) event.get("tenantId"),
                payload);
    }

    @SuppressWarnings("unchecked")
    private void handle(String message, boolean applicationAggregate) {
        Map<String, Object> event = json.readValue(message, Map.class);
        var payload = event.get("payload") instanceof Map<?, ?> value
                ? (Map<String, Object>) value
                : Map.<String, Object>of();
        var applicationId =
                applicationAggregate ? (String) event.get("aggregateId") : (String) payload.get("applicationId");
        try {
            communication.handleEvent(
                    String.valueOf(event.get("eventId")),
                    String.valueOf(event.get("eventType")),
                    (String) event.get("tenantId"),
                    applicationId,
                    payload);
        } catch (ResourceNotFoundException | IllegalArgumentException unprocessable) {
            LOG.warn(
                    "Skipped {} for application {}: {}",
                    event.get("eventType"),
                    applicationId,
                    unprocessable.getMessage());
        }
    }
}
