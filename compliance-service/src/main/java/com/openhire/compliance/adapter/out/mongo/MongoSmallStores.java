package com.openhire.compliance.adapter.out.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.openhire.compliance.application.port.CompliancePorts.ConsentStore;
import com.openhire.compliance.application.port.CompliancePorts.FairnessStore;
import com.openhire.compliance.application.port.CompliancePorts.HoldStore;
import com.openhire.compliance.application.port.CompliancePorts.PolicyStore;
import com.openhire.compliance.application.port.CompliancePorts.SelfIdentificationStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoConsentStore implements ConsentStore {
    private static final String COLLECTION = "consent_texts";
    private final MongoTemplate mongo;

    MongoConsentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Map<String, Object>> texts(String tenantId) {
        return Docs.list(
                mongo.getCollection(COLLECTION).find(eq("tenantId", tenantId)).sort(Sorts.descending("publishedAt")));
    }

    @Override
    public Optional<Map<String, Object>> active(String tenantId, String purpose) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(COLLECTION)
                .find(and(eq("tenantId", tenantId), eq("purpose", purpose), eq("status", "ACTIVE")))
                .first()));
    }

    @Override
    public void publish(Map<String, Object> text) {
        mongo.getCollection(COLLECTION)
                .updateMany(
                        and(
                                eq("tenantId", text.get("tenantId")),
                                eq("purpose", text.get("purpose")),
                                eq("status", "ACTIVE")),
                        Updates.set("status", "RETIRED"));
        mongo.getCollection(COLLECTION).insertOne(Docs.document(text));
    }
}

@Component
class MongoHoldStore implements HoldStore {
    private static final String COLLECTION = "legal_holds";
    private final MongoTemplate mongo;

    MongoHoldStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void insert(Map<String, Object> hold) {
        mongo.getCollection(COLLECTION).insertOne(Docs.document(hold));
    }

    @Override
    public Optional<Map<String, Object>> find(String tenantId, String id) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(COLLECTION)
                .find(and(eq("_id", id), eq("tenantId", tenantId)))
                .first()));
    }

    @Override
    public List<Map<String, Object>> list(String tenantId, String status) {
        Bson filter = status == null ? eq("tenantId", tenantId) : and(eq("tenantId", tenantId), eq("status", status));
        return Docs.list(mongo.getCollection(COLLECTION).find(filter).sort(Sorts.descending("placedAt")));
    }

    @Override
    public void replace(Map<String, Object> hold) {
        mongo.getCollection(COLLECTION).replaceOne(eq("_id", hold.get("_id")), Docs.document(hold));
    }

    @Override
    public List<Map<String, Object>> active(String tenantId) {
        return list(tenantId, "ACTIVE");
    }
}

@Component
class MongoPolicyStore implements PolicyStore {
    private static final String COLLECTION = "retention_policies";
    private final MongoTemplate mongo;

    MongoPolicyStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<Map<String, Object>> overrides(String tenantId) {
        return Docs.list(mongo.getCollection(COLLECTION).find(eq("tenantId", tenantId)));
    }

    @Override
    public void save(Map<String, Object> override) {
        mongo.getCollection(COLLECTION)
                .replaceOne(eq("_id", override.get("_id")), Docs.document(override), new ReplaceOptions().upsert(true));
    }
}

@Component
class MongoSelfIdentificationStore implements SelfIdentificationStore {
    private static final String COLLECTION = "self_identification";
    private final MongoTemplate mongo;

    MongoSelfIdentificationStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(String tenantId, String applicationId, String candidateId, String ciphertext, String at) {
        mongo.getCollection(COLLECTION)
                .replaceOne(
                        eq("_id", tenantId + "|" + applicationId),
                        new Document("_id", tenantId + "|" + applicationId)
                                .append("tenantId", tenantId)
                                .append("applicationId", applicationId)
                                .append("candidateId", candidateId)
                                .append("ciphertext", ciphertext)
                                .append("updatedAt", at),
                        new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<String> ciphertext(String tenantId, String applicationId) {
        var doc = mongo.getCollection(COLLECTION)
                .find(eq("_id", tenantId + "|" + applicationId))
                .first();
        return Optional.ofNullable(doc).map(d -> d.getString("ciphertext"));
    }

    @Override
    public Map<String, String> ciphertexts(String tenantId, List<String> applicationIds) {
        var out = new LinkedHashMap<String, String>();
        if (applicationIds.isEmpty()) return out;
        for (var doc : mongo.getCollection(COLLECTION)
                .find(and(eq("tenantId", tenantId), in("applicationId", applicationIds))))
            out.put(doc.getString("applicationId"), doc.getString("ciphertext"));
        return out;
    }

    @Override
    public void deleteByCandidate(String tenantId, String candidateId, List<String> applicationIds) {
        var filters = new ArrayList<Bson>();
        filters.add(eq("candidateId", candidateId));
        if (!applicationIds.isEmpty()) filters.add(in("applicationId", applicationIds));
        mongo.getCollection(COLLECTION)
                .deleteMany(and(eq("tenantId", tenantId), com.mongodb.client.model.Filters.or(filters)));
    }
}

@Component
class MongoFairnessStore implements FairnessStore {
    private static final String COLLECTION = "fairness_reports";
    private final MongoTemplate mongo;

    MongoFairnessStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Map<String, Object>> latest(String tenantId, String jobId, String dimension) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(COLLECTION)
                .find(eq("_id", tenantId + "|" + jobId + "|" + dimension))
                .first()));
    }

    @Override
    public void save(Map<String, Object> snapshot) {
        mongo.getCollection(COLLECTION)
                .replaceOne(eq("_id", snapshot.get("_id")), Docs.document(snapshot), new ReplaceOptions().upsert(true));
    }
}
