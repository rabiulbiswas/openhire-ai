package com.openhire.applicationservice.workflow;

import static com.openhire.support.Records.*;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Integrity signals are advisory flags next to the assessment: they never change a score or the stage. Raised signals
 * are added by signal id (so redelivery is harmless) and dismissed ones are removed again.
 */
@Component
class IntegritySignalConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(IntegritySignalConsumer.class);
    private static final int ATTEMPTS = 3;

    private final MongoTemplate mongo;

    IntegritySignalConsumer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @KafkaListener(topics = "integrity.events.v1", groupId = "application-integrity-signals-v1")
    public void consume(String message) {
        var event = Document.parse(message);
        String type = text(event, "eventType");
        if (!List.of("integrity.signal.raised.v1", "integrity.signal.dismissed.v1")
                .contains(type)) return;
        String tenant = required(event, "tenantId", 100);
        var payload = doc(event.get("payload"));
        String id = required(payload, "applicationId", 100), signalId = required(payload, "signalId", 200);
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var row = mongo.findOne(
                    Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(id)),
                    Document.class,
                    "submissions");
            if (row == null) {
                LOG.warn("Integrity signal for unknown application {}", id);
                return;
            }
            // An erased candidate's application must not receive evidence from a late event.
            if (row.containsKey("erasedAt")) return;
            var flags = new ArrayList<>(documents(row.get("integrityFlags")));
            boolean present = flags.stream().anyMatch(flag -> signalId.equals(text(flag, "id")));
            if (type.endsWith("raised.v1")) {
                if (present) return;
                flags.add(new Document("id", signalId)
                        .append("type", text(payload, "type"))
                        .append("severity", text(payload, "severity"))
                        .append("reason", text(payload, "evidence")));
            } else {
                if (!present) return;
                flags.removeIf(flag -> signalId.equals(text(flag, "id")));
            }
            if (mongo.updateFirst(guard(tenant, id, row), update(row, flags), "submissions")
                            .getMatchedCount()
                    > 0) return;
        }
        throw conflict("Application " + id + " kept changing; the event will be retried");
    }

    private static Query guard(String tenant, String id, Document row) {
        return Query.query(Criteria.where("tenantId")
                .is(tenant)
                .and("_id")
                .is(id)
                .and("revision")
                .is(row.get("revision")));
    }

    /** Mirrors the flags onto the assessment (when there is one) and keeps the flat flag the existing UI reads. */
    static Update update(Document row, List<Document> flags) {
        var update = new Update().set("integrityFlags", flags);
        boolean none = flags.isEmpty();
        var flat = none ? null : new Document("reason", text(flags.get(0), "reason"));
        if (none) update.unset("integrityFlag");
        else update.set("integrityFlag", true);
        if (row.containsKey("assessment")) {
            update.set("assessment.integrityFlags", flags);
            if (none) update.unset("assessment.integrityFlag");
            else update.set("assessment.integrityFlag", flat);
        }
        return update;
    }
}
