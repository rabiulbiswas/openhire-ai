package com.openhire.offer.adapter.in.kafka;

import com.openhire.offer.application.OfferApplicationService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** An application that is withdrawn or rejected rescinds any unanswered offer. Idempotent. */
@Component
class ApplicationEventsListener {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationEventsListener.class);
    private final OfferApplicationService offers;
    private final ObjectMapper json;

    ApplicationEventsListener(OfferApplicationService offers, ObjectMapper json) {
        this.offers = offers;
        this.json = json;
    }

    @KafkaListener(topics = "application.events.v1", groupId = "offer-application-events-v1")
    @SuppressWarnings("unchecked")
    void onApplicationEvent(String message) {
        Map<String, Object> event = json.readValue(message, Map.class);
        if (!"ApplicationStageChanged".equals(event.get("eventType"))) return;
        if (!(event.get("payload") instanceof Map<?, ?> payload)) return;
        var tenantId = (String) event.get("tenantId");
        var applicationId = (String) event.get("aggregateId");
        var target = String.valueOf(payload.get("to"));
        if (tenantId == null || applicationId == null) return;
        if (!"WITHDRAWN".equals(target) && !"REJECTED".equals(target)) return;
        try {
            offers.closeForApplication(
                    tenantId,
                    applicationId,
                    "WITHDRAWN".equals(target)
                            ? "The candidate withdrew their application"
                            : "The application was closed");
        } catch (IllegalStateException | IllegalArgumentException unprocessable) {
            LOG.warn("Offer not rescinded for application {}: {}", applicationId, unprocessable.getMessage());
        }
    }
}
