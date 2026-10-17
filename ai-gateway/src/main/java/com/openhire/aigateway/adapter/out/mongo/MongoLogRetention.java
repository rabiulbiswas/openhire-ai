package com.openhire.aigateway.adapter.out.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.client.model.Sorts;
import com.openhire.aigateway.application.port.LogRetention;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoLogRetention implements LogRetention {
    private final MongoTemplate mongo;
    private final String collection;

    MongoLogRetention(MongoTemplate mongo, @Value("${openhire.outbox.collection:ai_requests}") String collection) {
        this.mongo = mongo;
        this.collection = collection;
    }

    public List<String> tenants() {
        return mongo.getCollection(collection)
                .distinct("tenantId", String.class)
                .into(new ArrayList<>());
    }

    public long purge(String tenant, Instant before, boolean dryRun, int limit) {
        if (tenant == null || tenant.isBlank() || limit < 1 || limit > 500)
            throw new IllegalArgumentException("Invalid retention scope");
        var filter = and(eq("tenantId", tenant), lt("createdAt", Date.from(before)), exists("_events.0", false));
        var ids = new ArrayList<Object>();
        try (var cursor = mongo.getCollection(collection)
                .find(filter)
                .sort(Sorts.ascending("createdAt"))
                .projection(new org.bson.Document("_id", 1))
                .limit(limit)
                .cursor()) {
            while (cursor.hasNext()) ids.add(cursor.next().get("_id"));
        }
        if (dryRun || ids.isEmpty()) return ids.size();
        // Reapply eligibility at deletion time; pending outbox events are never discarded.
        return mongo.getCollection(collection)
                .deleteMany(and(filter, in("_id", ids)))
                .getDeletedCount();
    }
}
