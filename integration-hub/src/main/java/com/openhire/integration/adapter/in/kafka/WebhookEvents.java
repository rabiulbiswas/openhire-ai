package com.openhire.integration.adapter.in.kafka;

import com.openhire.integration.adapter.out.mongo.WebhookDeliveryQueue;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class WebhookEvents {
    private final WebhookDeliveryQueue queue;
    private final ObjectMapper json;

    WebhookEvents(WebhookDeliveryQueue queue, ObjectMapper json) {
        this.queue = queue;
        this.json = json;
    }

    @KafkaListener(
            topics = {"application.events.v1", "interview.events.v1", "offer.events.v1", "employee.events.v1"},
            groupId = "integration-webhooks-v1")
    void receive(String message) {
        var e = json.readValue(message, Map.class);
        var alias =
                switch (String.valueOf(e.get("eventType"))) {
                    case "ApplicationSubmitted" -> "application.created";
                    case "interview.scheduled.v1" -> "interview.scheduled";
                    case "offer.accepted.v1" -> "offer.accepted";
                    case "employee.created.v1" -> "employee.activated";
                    default -> null;
                };
        if (alias == null) return;
        queue.enqueue((String) e.get("tenantId"), (String) e.get("eventId"), alias, (String) e.get("aggregateId"));
    }
}
