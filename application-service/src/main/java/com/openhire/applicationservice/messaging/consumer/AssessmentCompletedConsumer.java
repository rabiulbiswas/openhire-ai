package com.openhire.applicationservice.messaging.consumer;

import static com.openhire.support.Records.*;

import com.openhire.applicationservice.service.AiAssessments;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Folds AI assessments into the application. AI recommends, people decide: this consumer writes the assessment and
 * score only. It never touches the stage, and it does not bump the revision, so a recruiter's open view is not
 * invalidated by a background result.
 */
@Component
public class AssessmentCompletedConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(AssessmentCompletedConsumer.class);
    private static final int ATTEMPTS = 3;

    private final MongoTemplate mongo;

    public AssessmentCompletedConsumer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @KafkaListener(topics = "assessment.events.v1", groupId = "application-assessment-completed-v1")
    public void consume(String message) {
        var event = Document.parse(message);
        if (!"assessment.completed.v1".equals(text(event, "eventType"))) return;
        String tenant = required(event, "tenantId", 100), eventId = required(event, "eventId", 100);
        var payload = doc(event.get("payload"));
        String id = required(payload, "applicationId", 100);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var row = mongo.findOne(
                    Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id)),
                    Document.class,
                    "submissions");
            if (row == null) {
                LOG.warn("Assessment for unknown application {}", id);
                return;
            }
            // An erased candidate's application must not receive evidence from a late event.
            if (row.containsKey("erasedAt")) return;
            var seen = new java.util.ArrayList<Object>(
                    (java.util.List<?>) row.getOrDefault("aiEventIds", java.util.List.of()));
            if (seen.contains(eventId)) return;
            var sets = AiAssessments.merge(row, payload);
            if (sets.isEmpty()) return;
            var update = new Update();
            sets.get().forEach(update::set);
            seen.add(eventId);
            update.set("aiEventIds", seen.subList(Math.max(0, seen.size() - 20), seen.size()));
            var guard = Query.query(Criteria.where("tenantId")
                    .is(tenant)
                    .and("_id")
                    .is(id)
                    .and("revision")
                    .is(row.get("revision")));
            if (mongo.updateFirst(guard, update, "submissions").getMatchedCount() > 0) return;
        }
        throw conflict("Application " + id + " kept changing; the event will be retried");
    }
}
