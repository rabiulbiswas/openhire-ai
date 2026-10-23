package com.openhire.integration.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Indexes;
import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.integration.service.IntegrationService.Store;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MongoIntegrationStore implements Store {
    private static final String COLLECTION = "integration_configs";
    private final MongoTemplate mongo;
    private final IntegrationBudget budget;

    MongoIntegrationStore(MongoTemplate mongo, IntegrationBudget budget) {
        this.mongo = mongo;
        this.budget = budget;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection(COLLECTION).createIndex(Indexes.ascending("tenantId", "kind"));
        mongo.getCollection(COLLECTION).createIndex(Indexes.ascending("tenantId", "kind", "_id"));
    }

    public List<IntegrationConfig> list(String tenant, String kind) {
        return page(tenant, kind, null, 200);
    }

    public List<IntegrationConfig> page(String tenant, String kind, String after, int limit) {
        var result = new ArrayList<IntegrationConfig>();
        for (var d : mongo.getCollection(COLLECTION)
                .find(
                        after == null || after.isBlank()
                                ? and(eq("tenantId", tenant), eq("kind", kind))
                                : and(eq("tenantId", tenant), eq("kind", kind), gt("_id", after)))
                .sort(new Document("_id", 1))
                .limit(limit))
            result.add(new IntegrationConfig(
                    d.getString("_id"),
                    tenant,
                    kind,
                    d.getString("name"),
                    d.getString("provider"),
                    d.getString("endpoint"),
                    d.getString("secretRef"),
                    d.getList("eventTypes", String.class),
                    d.getBoolean("enabled"),
                    ((Number) d.get("version")).longValue()));
        return result;
    }

    public boolean save(IntegrationConfig c, long expectedVersion) {
        if (expectedVersion == 0) budget.configuration(c.tenantId(), c.id());
        var document = new Document("_id", c.id())
                .append("tenantId", c.tenantId())
                .append("kind", c.kind())
                .append("name", c.name())
                .append("provider", c.provider())
                .append("endpoint", c.endpoint())
                .append("secretRef", c.secretRef())
                .append("eventTypes", c.eventTypes())
                .append("enabled", c.enabled())
                .append("version", c.version());
        if (expectedVersion == 0) {
            try {
                mongo.getCollection(COLLECTION).insertOne(document);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCode() == 11000) return false;
                throw e;
            }
        }
        return mongo.getCollection(COLLECTION)
                        .replaceOne(
                                and(
                                        eq("_id", c.id()),
                                        eq("tenantId", c.tenantId()),
                                        eq("kind", c.kind()),
                                        eq("version", expectedVersion)),
                                document)
                        .getModifiedCount()
                == 1;
    }

    public java.util.Optional<IntegrationConfig> find(String tenant, String kind, String id) {
        var d = mongo.getCollection(COLLECTION)
                .find(and(eq("_id", id), eq("tenantId", tenant), eq("kind", kind)))
                .first();
        return d == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new IntegrationConfig(
                        d.getString("_id"),
                        tenant,
                        kind,
                        d.getString("name"),
                        d.getString("provider"),
                        d.getString("endpoint"),
                        d.getString("secretRef"),
                        d.getList("eventTypes", String.class),
                        d.getBoolean("enabled"),
                        ((Number) d.get("version")).longValue()));
    }
}
