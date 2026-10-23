package com.openhire.talent.messaging.consumer;

import com.openhire.talent.service.ProfileProjector;
import com.openhire.talent.service.RediscoveryService;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inputs of the talent projection. Every handler is idempotent under redelivery: stage steps are keyed by event id,
 * profile writes are keyed by candidate, and a job-published run is keyed by its event id.
 */
@Component
class TalentEventsListener {
    private static final Set<String> APPLICATION_EVENTS =
            Set.of("ApplicationSubmitted", "ApplicationStageChanged", "ApplicationScreened");
    private static final String WORKFLOW_PRODUCER = "phase3-workflow";

    private final ProfileProjector projector;
    private final RediscoveryService rediscovery;
    private final ObjectMapper json;

    TalentEventsListener(ProfileProjector projector, RediscoveryService rediscovery, ObjectMapper json) {
        this.projector = projector;
        this.rediscovery = rediscovery;
        this.json = json;
    }

    @KafkaListener(topics = "application.events.v1", groupId = "talent-application-events-v1")
    void onApplicationEvent(String message) {
        var event = parse(message);
        var type = text(event, "eventType");
        // The legacy intake path shares the topic but has no application projection to read.
        if (!APPLICATION_EVENTS.contains(type) || !WORKFLOW_PRODUCER.equals(text(event, "producer"))) return;
        projector.onApplicationEvent(
                required(event, "tenantId"),
                required(event, "eventId"),
                type,
                required(event, "aggregateId"),
                occurredAt(event),
                map(event.get("payload")));
    }

    @KafkaListener(topics = "assessment.events.v1", groupId = "talent-assessment-events-v1")
    void onAssessmentEvent(String message) {
        var event = parse(message);
        if (!"assessment.completed.v1".equals(text(event, "eventType"))) return;
        var payload = map(event.get("payload"));
        projector.onAssessment(
                required(event, "tenantId"),
                required(payload, "applicationId"),
                text(payload, "status"),
                payload.get("score") instanceof Number score ? score.intValue() : null,
                notes(payload),
                occurredAt(event));
    }

    @KafkaListener(topics = "employee.events.v1", groupId = "talent-employee-events-v1")
    void onEmployeeEvent(String message) {
        var event = parse(message);
        if (!"employee.created.v1".equals(text(event, "eventType"))) return;
        var payload = map(event.get("payload"));
        projector.onEmployeeCreated(
                required(event, "tenantId"), text(payload, "candidateId"), text(payload, "applicationId"));
    }

    @KafkaListener(topics = "candidate.events.v1", groupId = "talent-candidate-events-v1")
    void onCandidateEvent(String message) {
        var event = parse(message);
        if (!"CandidateMerged".equals(text(event, "eventType"))) return;
        var payload = map(event.get("payload"));
        projector.onCandidateMerged(
                required(event, "tenantId"),
                required(payload, "sourceCandidateId"),
                required(payload, "survivorCandidateId"));
    }

    @KafkaListener(topics = "job.events.v1", groupId = "talent-job-events-v1")
    void onJobEvent(String message) {
        var event = parse(message);
        if (!"job.published.v1".equals(text(event, "eventType"))) return;
        var jobId = text(map(event.get("payload")), "jobId");
        rediscovery.onJobPublished(
                required(event, "tenantId"),
                jobId.isBlank() ? required(event, "aggregateId") : jobId,
                required(event, "eventId"));
    }

    /** Strengths and grounded quotes from the assessment; short statements, no contact data. */
    static List<String> notes(Map<String, Object> payload) {
        var notes = new ArrayList<String>();
        if (payload.get("strengths") instanceof List<?> strengths)
            strengths.stream().limit(3).forEach(value -> notes.add("Strength noted: " + shorten(value.toString())));
        if (payload.get("criteria") instanceof List<?> criteria)
            for (var item : criteria) {
                if (!(item instanceof Map<?, ?> criterion) || !Boolean.TRUE.equals(criterion.get("grounded"))) continue;
                if (notes.size() >= 6) break;
                if (criterion.get("evidence") instanceof List<?> quotes && !quotes.isEmpty())
                    notes.add(criterion.get("label") + ": \""
                            + shorten(quotes.getFirst().toString()) + "\"");
            }
        return notes;
    }

    private static String shorten(String value) {
        var text = value.strip();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static Instant occurredAt(Map<String, Object> event) {
        try {
            return Instant.parse(text(event, "occurredAt"));
        } catch (DateTimeParseException missing) {
            return Instant.now();
        }
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
