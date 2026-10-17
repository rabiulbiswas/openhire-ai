package com.openhire.interview.adapter.in.kafka;

import com.openhire.interview.application.InterviewApplicationService;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Observer of application lifecycle events. Moving an application to INTERVIEWING plans its loop from the
 * job's interview plan; rejection or withdrawal releases interviews that have not happened. Both are idempotent.
 */
@Component
class ApplicationEventsListener {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationEventsListener.class);
    private final InterviewApplicationService interviews;
    private final ObjectMapper json;

    ApplicationEventsListener(InterviewApplicationService interviews, ObjectMapper json) {
        this.interviews = interviews;
        this.json = json;
    }

    @KafkaListener(topics = "application.events.v1", groupId = "interview-application-events-v1")
    @SuppressWarnings("unchecked")
    void onApplicationEvent(String message) {
        Map<String, Object> event = json.readValue(message, Map.class);
        if (!"ApplicationStageChanged".equals(event.get("eventType"))) return;
        if (!(event.get("payload") instanceof Map<?, ?> payload)) return;
        var tenantId = (String) event.get("tenantId");
        var applicationId = (String) event.get("aggregateId");
        var target = String.valueOf(payload.get("to"));
        if (tenantId == null || applicationId == null) return;
        try {
            switch (target) {
                case "INTERVIEWING" ->
                    interviews.createLoop(tenantId, applicationId, String.valueOf(payload.get("actorId")));
                case "REJECTED", "WITHDRAWN" ->
                    interviews.closeForApplication(
                            tenantId,
                            applicationId,
                            "WITHDRAWN".equals(target) ? "The candidate withdrew" : "The application was closed");
                default -> {}
            }
        } catch (IllegalArgumentException | IllegalStateException | ResourceNotFoundException unprocessable) {
            // Retrying cannot fix missing plans or closed applications; record it and continue.
            LOG.warn("Interview loop not updated for application {}: {}", applicationId, unprocessable.getMessage());
        }
    }
}
