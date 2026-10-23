package com.openhire.compliance.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Sorts;
import com.openhire.compliance.domain.CanonicalJson;
import com.openhire.compliance.service.port.CompliancePorts.RecordRetention;
import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Verified archival copies: the original immutable ledger remains available for complete chain verification. */
@Component
class MongoRecordRetention implements RecordRetention {
    private final MongoTemplate mongo;
    private final ServiceClient ai;

    MongoRecordRetention(
            MongoTemplate mongo,
            Access access,
            @Value("${openhire.compliance.ai-url:http://localhost:8097}") String url) {
        this.mongo = mongo;
        this.ai = new ServiceClient(url, access.secret());
    }

    public List<String> tenants() {
        var tenants = new TreeSet<String>();
        mongo.getCollection("audit_events").distinct("tenantId", String.class).into(tenants);
        try {
            var response = ai.get("/api/v1/internal/ai/log-retention/tenants");
            if (response != null) tenants.addAll(response.getList("tenants", String.class));
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(MongoRecordRetention.class)
                    .warn(
                            "AI log tenant discovery unavailable; discovered audit tenants still run, AI-only tenants retry next sweep");
        }
        return new ArrayList<>(tenants);
    }

    public Map<String, Object> run(String tenant, String policy, String cutoff, boolean dryRun, int limit) {
        if (tenant == null || tenant.isBlank() || limit < 1 || limit > 500)
            throw new IllegalArgumentException("Invalid retention scope");
        if ("ai.request-logs".equals(policy))
            return ai.post(
                    "/api/v1/internal/ai/log-retention",
                    Map.of("tenantId", tenant, "cutoff", cutoff, "dryRun", dryRun, "limit", limit));
        if (!"audit.events".equals(policy)) throw new IllegalArgumentException("Unknown record policy");
        var archive = mongo.getCollection("audit_archive");
        var pipeline = List.of(
                Aggregates.match(and(eq("tenantId", tenant), lt("at", cutoff))),
                Aggregates.sort(Sorts.ascending("at", "seq")),
                Aggregates.lookup("audit_archive", "_id", "_id", "archived"),
                Aggregates.match(exists("archived.0", false)),
                Aggregates.limit(limit));
        int count = 0;
        try (var cursor = mongo.getCollection("audit_events")
                .aggregate(pipeline)
                .batchSize(limit)
                .cursor()) {
            while (cursor.hasNext()) {
                var original = cursor.next();
                original.remove("archived");
                if (!dryRun) {
                    var digest = CanonicalJson.sha256(CanonicalJson.write(original));
                    var copy = new Document("_id", original.get("_id"))
                            .append("tenantId", tenant)
                            .append("record", original)
                            .append("sha256", digest)
                            .append("archivedAt", new java.util.Date());
                    try {
                        archive.insertOne(copy);
                    } catch (MongoWriteException duplicate) {
                        if (duplicate.getError().getCode() != 11000) throw duplicate;
                    }
                    var stored = archive.find(eq("_id", original.get("_id"))).first();
                    if (stored == null
                            || !digest.equals(stored.getString("sha256"))
                            || !digest.equals(CanonicalJson.sha256(CanonicalJson.write(stored.get("record")))))
                        throw new IllegalStateException("Audit archive verification failed");
                }
                count++;
            }
        }
        return Map.of(
                "key",
                policy,
                "status",
                "SUCCESS",
                "dryRun",
                dryRun,
                "eligibleInBatch",
                count,
                "processed",
                dryRun ? 0 : count,
                "limit",
                limit);
    }
}
