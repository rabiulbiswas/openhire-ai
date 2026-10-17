package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.*;

import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The hire saga's employee record closes the application: OFFER becomes HIRED and, by policy, the candidate's other
 * active applications are rejected as HIRED_ELSEWHERE. Both writes are idempotent under redelivery.
 */
@Component
public class EmployeeCreatedConsumer {
    static final String ACTOR = "employee-service";
    static final String HIRED_ELSEWHERE = "HIRED_ELSEWHERE";
    private static final Logger LOG = LoggerFactory.getLogger(EmployeeCreatedConsumer.class);
    private static final Set<String> CLOSED = Set.of("HIRED", "REJECTED", "WITHDRAWN");
    private static final int ATTEMPTS = 3;

    private final MongoTemplate mongo;
    private final boolean closeOthers;

    public EmployeeCreatedConsumer(
            MongoTemplate mongo, @Value("${openhire.application.close-other-applications:true}") boolean closeOthers) {
        this.mongo = mongo;
        this.closeOthers = closeOthers;
    }

    @KafkaListener(topics = "employee.events.v1", groupId = "application-employee-created-v1")
    public void consume(String message) {
        var envelope = Document.parse(message);
        if (!"employee.created.v1".equals(text(envelope, "eventType"))) return;
        String tenant = required(envelope, "tenantId", 100);
        String applicationId = required(doc(envelope.get("payload")), "applicationId", 100);
        var row = mongo.findOne(key(tenant, applicationId), Document.class, "submissions");
        if (row == null) {
            LOG.warn("Employee created for unknown application {}", applicationId);
            return;
        }
        String stage = text(row, "stage");
        if (stage.equals("OFFER")) transition(tenant, applicationId, "HIRED", "Hired", false);
        else if (!stage.equals("HIRED"))
            LOG.warn("Application {} is {} and cannot become HIRED; left unchanged", applicationId, stage);
        if (closeOthers
                && !text(row, "candidateId").isBlank()
                && Set.of("OFFER", "HIRED").contains(stage))
            closeOthers(tenant, applicationId, text(row, "candidateId"));
    }

    private void closeOthers(String tenant, String hiredId, String candidateId) {
        var others = mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenant)
                        .and("candidateId")
                        .is(candidateId)
                        .and("_id")
                        .ne(hiredId)
                        .and("stage")
                        .nin(CLOSED)),
                Document.class,
                "submissions");
        for (var other : others) transition(tenant, other.getString("_id"), "REJECTED", HIRED_ELSEWHERE, true);
    }

    /** Applies the move guarded by the row revision, re-reading when another writer got there first. */
    private void transition(String tenant, String id, String target, String reason, boolean closing) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var row = mongo.findOne(key(tenant, id), Document.class, "submissions");
            if (row == null) return;
            String stage = text(row, "stage");
            if (CLOSED.contains(stage) || (!closing && !stage.equals("OFFER"))) return;
            var at = now();
            var update = new Update()
                    .set("stage", target)
                    .set("updatedAt", at)
                    .set("decidedBy", ACTOR)
                    .set("projectionDirty", true)
                    .set("previousStage", stage)
                    .set("previousProcessingState", text(row, "processingState"))
                    .set("lastDecision", false)
                    .set("processingState", "COMPLETE")
                    .unset("lease")
                    .inc("revision", 1)
                    .push(
                            "decisions",
                            new Document("from", stage)
                                    .append("to", target)
                                    .append("actor", ACTOR)
                                    .append("reason", reason)
                                    .append("at", at))
                    .push(
                            "_events",
                            event(
                                    "ApplicationStageChanged",
                                    tenant,
                                    "Application",
                                    id,
                                    closing
                                            ? Map.of("from", stage, "to", target, "actorId", ACTOR, "reason", reason)
                                            : Map.of("from", stage, "to", target, "actorId", ACTOR)));
            var guard = key(tenant, id).addCriteria(Criteria.where("revision").is(row.get("revision")));
            if (mongo.updateFirst(guard, update, "submissions").getMatchedCount() > 0) return;
        }
        throw conflict("Application " + id + " kept changing; the event will be retried");
    }

    private static Query key(String tenant, String id) {
        return Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id));
    }
}
