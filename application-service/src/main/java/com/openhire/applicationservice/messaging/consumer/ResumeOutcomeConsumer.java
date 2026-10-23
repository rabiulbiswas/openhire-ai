package com.openhire.applicationservice.messaging.consumer;

import static com.openhire.support.Records.*;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ResumeOutcomeConsumer {
    private final MongoTemplate mongo;

    public ResumeOutcomeConsumer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @KafkaListener(topics = "resume.events.v1", groupId = "application-resume-outcomes-v1")
    public void consume(String message) {
        var event = Document.parse(message);
        if (!java.util.Set.of("ResumeProcessed", "ResumeProcessingFailed").contains(text(event, "eventType"))) return;
        var payload = doc(event.get("payload"));
        // A replaced resume's late event cannot overwrite the new upload or a human decision.
        mongo.updateFirst(
                Query.query(Criteria.where("tenantId")
                        .is(required(event, "tenantId", 100))
                        .and("_id")
                        .is(required(payload, "applicationId", 100))
                        .and("resumeId")
                        .is(required(event, "aggregateId", 100))
                        .and("processingState")
                        .is("WAITING")),
                new Update().set("nextAttempt", now()),
                "submissions");
    }
}
