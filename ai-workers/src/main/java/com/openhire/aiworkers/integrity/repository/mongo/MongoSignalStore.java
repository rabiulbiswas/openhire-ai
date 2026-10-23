package com.openhire.aiworkers.integrity.repository.mongo;

import com.openhire.aiworkers.integrity.domain.Signal;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.SignalStore;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Signals carry their outgoing events (embedded outbox relayed to {@code integrity.events.v1}). */
@Repository
class MongoSignalStore implements SignalStore {
    static final String COLLECTION = "integrity_signals";
    private static final String TUNING = "integrity_dismissals";
    private final MongoTemplate mongo;

    MongoSignalStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean insertIfAbsent(Signal s, Map<String, Object> event) {
        try {
            mongo.insert(
                    new Document("_id", s.id())
                            .append("tenantId", s.tenantId())
                            .append("applicationId", s.applicationId())
                            .append("candidateId", s.candidateId())
                            .append("resumeId", s.resumeId())
                            .append("type", s.type())
                            .append("severity", s.severity())
                            .append("confidence", s.confidence())
                            .append("evidence", s.evidence())
                            .append("status", s.status())
                            .append("createdAt", Date.from(s.createdAt()))
                            .append("_events", List.of(event)),
                    COLLECTION);
            return true;
        } catch (DuplicateKeyException exists) {
            return false;
        }
    }

    @Override
    public List<Signal> byApplication(String tenantId, String applicationId) {
        return mongo
                .find(
                        Query.query(Criteria.where("tenantId")
                                        .is(tenantId)
                                        .and("applicationId")
                                        .is(applicationId))
                                .with(Sort.by("createdAt")),
                        Document.class,
                        COLLECTION)
                .stream()
                .map(MongoSignalStore::signal)
                .toList();
    }

    @Override
    public Optional<Signal> find(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("_id")
                                .is(id)),
                        Document.class,
                        COLLECTION))
                .map(MongoSignalStore::signal);
    }

    @Override
    public Optional<Signal> dismiss(
            String tenantId, String id, String actor, String reason, Map<String, Object> event) {
        var now = new Date();
        var row = mongo.findAndModify(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("_id")
                        .is(id)
                        .and("status")
                        .is(Signal.RAISED)),
                new Update()
                        .set("status", Signal.DISMISSED)
                        .set("dismissedBy", actor)
                        .set("dismissReason", reason)
                        .set("dismissedAt", now)
                        .push("_events", event),
                FindAndModifyOptions.options().returnNew(true),
                Document.class,
                COLLECTION);
        if (row == null) return find(tenantId, id);
        mongo.insert(
                new Document("tenantId", tenantId)
                        .append("signalId", id)
                        .append("type", row.getString("type"))
                        .append("severity", row.getString("severity"))
                        .append("confidence", row.get("confidence"))
                        .append("reason", reason)
                        .append("by", actor)
                        .append("at", now),
                TUNING);
        return Optional.of(signal(row));
    }

    private static Signal signal(Document row) {
        return new Signal(
                row.getString("_id"),
                row.getString("tenantId"),
                row.getString("applicationId"),
                row.getString("candidateId"),
                row.getString("resumeId"),
                row.getString("type"),
                row.getString("severity"),
                ((Number) row.get("confidence")).doubleValue(),
                row.getString("evidence"),
                row.getString("status"),
                row.getDate("createdAt").toInstant(),
                row.getString("dismissedBy"),
                row.getString("dismissReason"),
                row.getDate("dismissedAt") == null
                        ? null
                        : row.getDate("dismissedAt").toInstant());
    }
}
