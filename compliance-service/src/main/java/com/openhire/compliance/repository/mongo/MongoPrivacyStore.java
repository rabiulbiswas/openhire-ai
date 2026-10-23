package com.openhire.compliance.repository.mongo;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.openhire.compliance.service.port.CompliancePorts.PrivacyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * privacy_requests and privacy_exports. Export sections are kept as JSON text so their keys are never constrained by
 * MongoDB field-name rules; the assembled export is deleted when it expires (TTL index on purgeAt).
 */
@Component
class MongoPrivacyStore implements PrivacyStore {
    static final String REQUESTS = "privacy_requests";
    static final String EXPORTS = "privacy_exports";
    private static final List<String> OPEN = List.of("REQUESTED", "BLOCKED_BY_LEGAL_HOLD", "IN_PROGRESS");

    private final MongoTemplate mongo;
    private final ObjectMapper json;

    MongoPrivacyStore(MongoTemplate mongo, ObjectMapper json) {
        this.mongo = mongo;
        this.json = json;
    }

    @Override
    public void insert(Map<String, Object> request) {
        mongo.getCollection(REQUESTS).insertOne(Docs.document(request));
    }

    @Override
    public Optional<Map<String, Object>> find(String tenantId, String id) {
        return Optional.ofNullable(Docs.map(mongo.getCollection(REQUESTS)
                .find(and(eq("_id", id), eq("tenantId", tenantId)))
                .first()));
    }

    @Override
    public List<Map<String, Object>> list(String tenantId, String status, String candidateId, int limit) {
        var filters = new ArrayList<Bson>();
        filters.add(eq("tenantId", tenantId));
        if (status != null) filters.add(eq("status", status));
        if (candidateId != null) filters.add(eq("candidateId", candidateId));
        return Docs.list(mongo.getCollection(REQUESTS)
                .find(and(filters))
                .sort(Sorts.descending("createdAt"))
                .limit(limit));
    }

    @Override
    public List<Map<String, Object>> withStatus(String status, int limit) {
        return Docs.list(mongo.getCollection(REQUESTS)
                .find(eq("status", status))
                .sort(Sorts.ascending("createdAt"))
                .limit(limit));
    }

    @Override
    public boolean replace(Map<String, Object> request, List<String> from) {
        return mongo.getCollection(REQUESTS)
                        .replaceOne(and(eq("_id", request.get("_id")), in("status", from)), Docs.document(request))
                        .getMatchedCount()
                > 0;
    }

    @Override
    public Optional<Map<String, Object>> recordAck(
            String tenantId, String id, String service, Map<String, Object> entry, Map<String, Object> section) {
        var field = "participants." + service;
        var updated = mongo.getCollection(REQUESTS)
                .findOneAndUpdate(
                        and(
                                eq("_id", id),
                                eq("tenantId", tenantId),
                                eq("status", "IN_PROGRESS"),
                                eq(field + ".status", "PENDING")),
                        Updates.set(field, Docs.document(entry)),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        if (updated == null) return Optional.empty();
        if (section != null)
            mongo.getCollection(EXPORTS)
                    .updateOne(
                            eq("_id", id),
                            Updates.combine(
                                    Updates.set("sectionsJson." + service, json.writeValueAsString(section)),
                                    Updates.setOnInsert("tenantId", tenantId),
                                    Updates.setOnInsert("candidateId", updated.getString("candidateId")),
                                    Updates.setOnInsert("createdAt", new Date())),
                            new UpdateOptions().upsert(true));
        return Optional.of(Docs.map(updated));
    }

    @Override
    public void markStuck(String tenantId, String id) {
        mongo.getCollection(REQUESTS)
                .updateOne(
                        and(eq("_id", id), eq("tenantId", tenantId), eq("status", "IN_PROGRESS")),
                        Updates.set("stuck", true));
    }

    @Override
    public void anonymise(String tenantId, String candidateId, String candidateIdHash) {
        mongo.getCollection(REQUESTS)
                .updateMany(
                        and(eq("tenantId", tenantId), eq("candidateId", candidateId)),
                        Updates.combine(Updates.set("candidateIdHash", candidateIdHash), Updates.unset("candidateId")));
        // A candidate who made the request is recorded as its requester; keep only the hash there as well.
        mongo.getCollection(REQUESTS)
                .updateMany(
                        and(eq("tenantId", tenantId), eq("requestedBy", candidateId)),
                        Updates.set("requestedBy", candidateIdHash));
        mongo.getCollection(EXPORTS).deleteMany(and(eq("tenantId", tenantId), eq("candidateId", candidateId)));
    }

    @Override
    public void saveExport(String tenantId, String requestId, Map<String, Object> export, String purgeAt) {
        mongo.getCollection(EXPORTS)
                .updateOne(
                        eq("_id", requestId),
                        Updates.combine(
                                Updates.set("finalJson", json.writeValueAsString(export)),
                                Updates.unset("sectionsJson"),
                                Updates.set("purgeAt", Date.from(Instant.parse(purgeAt))),
                                Updates.set("tenantId", tenantId),
                                Updates.set("candidateId", export.get("candidateId")),
                                Updates.setOnInsert("createdAt", new Date())),
                        new UpdateOptions().upsert(true));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> export(String tenantId, String requestId) {
        var doc = mongo.getCollection(EXPORTS)
                .find(and(eq("_id", requestId), eq("tenantId", tenantId)))
                .first();
        if (doc == null) return Optional.empty();
        if (doc.getString("finalJson") != null)
            return Optional.of(json.readValue(doc.getString("finalJson"), Map.class));
        var sections = new LinkedHashMap<String, Object>();
        var partial = doc.get("sectionsJson", Document.class);
        if (partial != null)
            partial.forEach((service, text) -> sections.put(service, json.readValue((String) text, Map.class)));
        return Optional.of(Map.of("sections", sections));
    }

    @Override
    public long countOpen(String tenantId) {
        return mongo.getCollection(REQUESTS).countDocuments(and(eq("tenantId", tenantId), in("status", OPEN)));
    }

    @Override
    public long countStuck(String tenantId) {
        return mongo.getCollection(REQUESTS)
                .countDocuments(and(eq("tenantId", tenantId), eq("status", "IN_PROGRESS"), eq("stuck", true)));
    }
}
