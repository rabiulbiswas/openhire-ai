package com.openhire.compliance.adapter.out.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Sorts;
import com.openhire.compliance.application.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.application.port.CompliancePorts.AuditStore;
import com.openhire.compliance.application.port.CompliancePorts.InsertResult;
import com.openhire.compliance.domain.AuditEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * audit_events. The unique (tenantId, seq) index is what makes appends atomic: two writers racing for the same seq
 * cannot both succeed, and the loser re-reads the head and tries the next one, so the chain has no gaps.
 */
@Component
class MongoAuditStore implements AuditStore {
    static final String COLLECTION = "audit_events";
    static final String SEQ_INDEX = "seq_unique";
    static final String EVENT_INDEX = "event_unique";
    private final MongoTemplate mongo;

    MongoAuditStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<AuditEntry> last(String tenantId) {
        var found = mongo.getCollection(COLLECTION)
                .find(eq("tenantId", tenantId))
                .sort(Sorts.descending("seq"))
                .limit(1)
                .first();
        return Optional.ofNullable(found).map(d -> AuditEntry.fromMap(Docs.map(d)));
    }

    @Override
    public InsertResult insert(AuditEntry entry) {
        try {
            mongo.getCollection(COLLECTION).insertOne(Docs.document(entry.toMap()));
            return InsertResult.INSERTED;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
            return e.getError().getMessage().contains(EVENT_INDEX)
                    ? InsertResult.DUPLICATE_EVENT
                    : InsertResult.SEQ_CONFLICT;
        }
    }

    @Override
    public List<AuditEntry> page(String tenantId, AuditFilter f, Long beforeSeq, int limit) {
        var filters = new ArrayList<Bson>();
        filters.add(eq("tenantId", tenantId));
        if (f.actor() != null) filters.add(eq("actor.id", f.actor()));
        if (f.action() != null) filters.add(eq("action", f.action()));
        if (f.resourceType() != null) filters.add(eq("resource.type", f.resourceType()));
        if (f.resourceId() != null) filters.add(eq("resource.id", f.resourceId()));
        if (f.candidateId() != null) filters.add(eq("candidateId", f.candidateId()));
        if (f.applicationId() != null) filters.add(eq("applicationId", f.applicationId()));
        if (f.from() != null) filters.add(gte("at", f.from()));
        if (f.to() != null) filters.add(lte("at", f.to()));
        if (beforeSeq != null) filters.add(lt("seq", beforeSeq));
        return read(mongo.getCollection(COLLECTION)
                .find(and(filters))
                .sort(Sorts.descending("seq"))
                .limit(limit));
    }

    @Override
    public List<AuditEntry> after(String tenantId, long afterSeq, String from, String to, int limit) {
        var filters = new ArrayList<Bson>();
        filters.add(eq("tenantId", tenantId));
        filters.add(gt("seq", afterSeq));
        if (from != null) filters.add(gte("at", from));
        if (to != null) filters.add(lte("at", to));
        return read(mongo.getCollection(COLLECTION)
                .find(and(filters))
                .sort(Sorts.ascending("seq"))
                .limit(limit));
    }

    @Override
    public long countSince(String tenantId, String from) {
        return mongo.getCollection(COLLECTION).countDocuments(and(eq("tenantId", tenantId), gte("at", from)));
    }

    private static List<AuditEntry> read(Iterable<Document> found) {
        var out = new ArrayList<AuditEntry>();
        for (var d : found) out.add(AuditEntry.fromMap(Docs.map(d)));
        return out;
    }
}
