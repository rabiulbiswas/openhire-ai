package com.openhire.applicationservice.messaging.consumer;

import static com.openhire.support.Records.*;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reassignment is idempotent: redelivery no longer matches the source candidate ID. */
@Component
public class CandidateMergeConsumer {
    private final MongoTemplate mongo;

    public CandidateMergeConsumer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @KafkaListener(topics = "candidate.events.v1", groupId = "application-candidate-merges-v1")
    public void consume(String message) {
        var envelope = Document.parse(message);
        if (!"CandidateMerged".equals(text(envelope, "eventType"))) return;
        String tenant = required(envelope, "tenantId", 100);
        var payload = doc(envelope.get("payload"));
        String source = required(payload, "sourceCandidateId", 100),
                target = required(payload, "survivorCandidateId", 100);
        if (source.equals(target)) throw new IllegalArgumentException("Invalid candidate merge event");
        mongo.updateMulti(
                Query.query(
                        Criteria.where("tenantId").is(tenant).and("candidateId").is(source)),
                new Update()
                        .set("candidateId", target)
                        .set("projectionDirty", true)
                        .set("updatedAt", now())
                        .inc("revision", 1),
                "submissions");
    }
}
