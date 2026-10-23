package com.openhire.communication.messaging.consumer;

import com.openhire.communication.service.CommunicationService;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Mirrors dispatcher delivery outcomes onto messages so staff can see failures. */
@Component
class DeliveryUpdatesListener {
    private final CommunicationService communication;
    private final ObjectMapper json;

    DeliveryUpdatesListener(CommunicationService communication, ObjectMapper json) {
        this.communication = communication;
        this.json = json;
    }

    @KafkaListener(topics = "communication.delivery.v1", groupId = "communication-delivery-v1")
    @SuppressWarnings("unchecked")
    void onDelivery(String message) {
        Map<String, Object> event = json.readValue(message, Map.class);
        if (!"communication.delivery.updated.v1".equals(event.get("eventType"))) return;
        if (!(event.get("payload") instanceof Map<?, ?> payload)) return;
        var messageId = payload.get("messageId");
        var status = payload.get("status");
        if (messageId == null || status == null) return;
        communication.applyDelivery(
                messageId.toString(),
                status.toString(),
                payload.get("reason") == null ? null : payload.get("reason").toString());
    }
}
