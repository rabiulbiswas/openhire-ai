package com.openhire.integration.adapter.in.kafka;

import com.openhire.integration.adapter.out.mongo.CalendarWorkQueue;
import com.openhire.integration.domain.CalendarEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class InterviewCalendarSync {
    private final MongoTemplate mongo;
    private final CalendarWorkQueue queue;
    private final ObjectMapper json;

    public InterviewCalendarSync(MongoTemplate mongo, CalendarWorkQueue queue, ObjectMapper json) {
        this.mongo = mongo;
        this.queue = queue;
        this.json = json;
    }

    @KafkaListener(topics = "interview.events.v1", groupId = "integration-interview-calendar-v1")
    @SuppressWarnings("unchecked")
    public void receive(String message) {
        var event = json.readValue(message, Map.class);
        String type = String.valueOf(event.get("eventType"));
        if (!List.of("interview.scheduled.v1", "interview.rescheduled.v1", "interview.cancelled.v1")
                .contains(type)) return;
        String tenant = (String) event.get("tenantId");
        var binding = mongo.getCollection("calendar_sync_bindings")
                .find(new Document("_id", tenant).append("enabled", true))
                .first();
        if (binding == null) return;
        Instant occurred = Instant.parse((String) event.get("occurredAt"));
        // Enabling sync never replays historical interviews from before the explicit opt-in.
        if (occurred.isBefore(binding.getDate("enabledAt").toInstant())) return;
        var payload = (Map<String, Object>) event.get("payload");
        String resource = "interview_" + payload.get("roundId");
        boolean cancel = type.equals("interview.cancelled.v1");
        CalendarEvent desired = null;
        if (!cancel) {
            var panel = payload.get("panel") instanceof List<?> list ? list : List.of();
            List<String> emails = panel.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> ((Map<?, ?>) item).get("email"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(email -> !email.isBlank())
                    .distinct()
                    .toList();
            desired = new CalendarEvent(
                    "Interview: " + payload.get("roundName"),
                    Instant.parse((String) payload.get("start")),
                    Instant.parse((String) payload.get("end")),
                    (String) payload.get("timezone"),
                    emails,
                    false);
        }
        queue.enqueue(
                tenant,
                binding.getString("connector"),
                resource,
                (String) event.get("eventId"),
                null,
                desired,
                cancel,
                occurred);
    }
}
