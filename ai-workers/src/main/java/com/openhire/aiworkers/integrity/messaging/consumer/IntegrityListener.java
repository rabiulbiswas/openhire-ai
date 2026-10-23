package com.openhire.aiworkers.integrity.messaging.consumer;

import com.openhire.aiworkers.integrity.service.IntegrityService;
import java.util.Map;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class IntegrityListener {
    private final IntegrityService integrity;
    private final ObjectMapper json;

    IntegrityListener(IntegrityService integrity, ObjectMapper json) {
        this.integrity = integrity;
        this.json = json;
    }

    @KafkaListener(topics = "resume.events.v1", groupId = "ai-workers-integrity-v1")
    void onResumeEvent(String message) {
        var event = map(json.readValue(message, Map.class));
        if (!"ResumeProcessed".equals(event.get("eventType"))) return;
        var payload = map(event.get("payload"));
        integrity.analyze(
                required(event, "tenantId"), required(payload, "applicationId"), required(event, "aggregateId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String required(Map<String, Object> source, String key) {
        var value = source.get(key) == null ? "" : source.get(key).toString().trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
