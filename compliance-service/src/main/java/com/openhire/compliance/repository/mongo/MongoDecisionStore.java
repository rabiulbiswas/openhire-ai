package com.openhire.compliance.repository.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;

import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.openhire.compliance.service.port.CompliancePorts.DecisionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoDecisionStore implements DecisionStore {
    static final String COLLECTION = "ai_decisions";
    private final MongoTemplate mongo;

    MongoDecisionStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(Map<String, Object> decision) {
        mongo.getCollection(COLLECTION)
                .replaceOne(eq("_id", decision.get("_id")), Docs.document(decision), new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<Map<String, Object>> find(String tenantId, String id) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(COLLECTION)
                .find(eq("_id", tenantId + "|" + id))
                .first()));
    }

    @Override
    public List<Map<String, Object>> list(
            String tenantId, String candidateId, String applicationId, String task, String from, int limit) {
        var filters = new ArrayList<Bson>();
        filters.add(eq("tenantId", tenantId));
        if (candidateId != null) filters.add(eq("candidateId", candidateId));
        if (applicationId != null) filters.add(eq("applicationId", applicationId));
        if (task != null) filters.add(eq("task", task));
        if (from != null) filters.add(gte("at", from));
        return Docs.list(mongo.getCollection(COLLECTION)
                .find(and(filters))
                .sort(Sorts.descending("at"))
                .limit(limit));
    }

    @Override
    public void deleteByCandidate(String tenantId, String candidateId) {
        mongo.getCollection(COLLECTION).deleteMany(and(eq("tenantId", tenantId), eq("candidateId", candidateId)));
    }
}
