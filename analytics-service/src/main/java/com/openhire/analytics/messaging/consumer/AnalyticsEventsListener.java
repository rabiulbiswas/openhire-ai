package com.openhire.analytics.messaging.consumer;

import com.openhire.analytics.domain.Envelope;
import com.openhire.analytics.service.ProjectionService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One consumer group per topic. A message that is not a JSON event is logged and skipped; a storage failure
 * propagates to the shared error handler (retry, then dead letter) so no event is silently lost.
 */
@Component
class AnalyticsEventsListener {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsEventsListener.class);

    private final ProjectionService projections;
    private final ObjectMapper json;

    AnalyticsEventsListener(ProjectionService projections, ObjectMapper json) {
        this.projections = projections;
        this.json = json;
    }

    @KafkaListener(topics = "application.events.v1", groupId = "analytics-application-events-v1")
    void onApplicationEvent(String message) {
        handle(message, projections::onApplicationEvent);
    }

    @KafkaListener(topics = "assessment.events.v1", groupId = "analytics-assessment-events-v1")
    void onAssessmentEvent(String message) {
        handle(message, projections::onAssessmentEvent);
    }

    @KafkaListener(topics = "offer.events.v1", groupId = "analytics-offer-events-v1")
    void onOfferEvent(String message) {
        handle(message, projections::onOfferEvent);
    }

    @KafkaListener(topics = "resume.events.v1", groupId = "analytics-resume-events-v1")
    void onResumeEvent(String message) {
        handle(message, projections::onResumeEvent);
    }

    @KafkaListener(topics = "communication.commands.v1", groupId = "analytics-communication-commands-v1")
    void onCommunicationCommand(String message) {
        handle(message, projections::onCommunicationCommand);
    }

    @KafkaListener(topics = "communication.delivery.v1", groupId = "analytics-communication-delivery-v1")
    void onDeliveryEvent(String message) {
        handle(message, projections::onDeliveryEvent);
    }

    @KafkaListener(topics = "ai.events.v1", groupId = "analytics-ai-events-v1")
    void onAiEvent(String message) {
        handle(message, projections::onAiEvent);
    }

    private void handle(String message, Consumer<Envelope> projection) {
        var envelope = parse(message);
        if (envelope != null) projection.accept(envelope);
    }

    @SuppressWarnings("unchecked")
    Envelope parse(String message) {
        try {
            var event = json.readValue(message, Map.class);
            var payload = event.get("payload") instanceof Map<?, ?> value
                    ? (Map<String, Object>) value
                    : Map.<String, Object>of();
            return new Envelope(
                    text(event.get("eventId")),
                    text(event.get("eventType")),
                    text(event.get("tenantId")),
                    text(event.get("aggregateId")),
                    instant(text(event.get("occurredAt"))),
                    payload);
        } catch (RuntimeException malformed) {
            LOG.warn(
                    "Skipped a message that is not a JSON event: {}",
                    malformed.getClass().getSimpleName());
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Instant instant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
