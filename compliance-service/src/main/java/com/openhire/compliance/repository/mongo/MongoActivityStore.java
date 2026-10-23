package com.openhire.compliance.repository.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;

import com.mongodb.client.model.Sorts;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoActivityStore implements ActivityStore {
    static final String COLLECTION = "subject_activity";
    private final MongoTemplate mongo;

    MongoActivityStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Map<String, Object>> find(String tenantId, String applicationId) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(COLLECTION)
                .find(eq("_id", tenantId + "|" + applicationId))
                .first()));
    }

    @Override
    public boolean save(Map<String, Object> doc) {
        long version = ((Number) doc.get("version")).longValue();
        var next = Docs.document(doc);
        next.put("version", version + 1);
        try {
            if (version == 0) mongo.getCollection(COLLECTION).insertOne(next);
            else if (mongo.getCollection(COLLECTION)
                            .replaceOne(and(eq("_id", doc.get("_id")), eq("version", version)), next)
                            .getMatchedCount()
                    == 0) return false;
        } catch (RuntimeException e) {
            if (Docs.duplicate(e)) return false;
            throw e;
        }
        doc.put("version", version + 1);
        return true;
    }

    @Override
    public List<Map<String, Object>> byCandidate(String tenantId, String candidateId) {
        return Docs.list(
                mongo.getCollection(COLLECTION).find(and(eq("tenantId", tenantId), eq("candidateId", candidateId))));
    }

    @Override
    public List<Map<String, Object>> byJob(String tenantId, String jobId) {
        return Docs.list(mongo.getCollection(COLLECTION).find(and(eq("tenantId", tenantId), eq("jobId", jobId))));
    }

    @Override
    public List<Map<String, Object>> all(String tenantId) {
        return Docs.list(mongo.getCollection(COLLECTION).find(eq("tenantId", tenantId)));
    }

    /** Idle applications that are closed; in-progress ones are left out so they cannot crowd the scan window. */
    @Override
    public List<Map<String, Object>> idleSince(String tenantId, String cutoff, int limit) {
        return Docs.list(mongo.getCollection(COLLECTION)
                .find(and(eq("tenantId", tenantId), lt("lastActivityAt", cutoff), ne("lifecycle", "ACTIVE")))
                .sort(Sorts.ascending("lastActivityAt"))
                .limit(limit));
    }

    @Override
    public List<String> tenants() {
        return mongo.getCollection(COLLECTION)
                .distinct("tenantId", String.class)
                .into(new java.util.ArrayList<>());
    }

    @Override
    public void deleteByCandidate(String tenantId, String candidateId) {
        mongo.getCollection(COLLECTION).deleteMany(and(eq("tenantId", tenantId), eq("candidateId", candidateId)));
    }
}
