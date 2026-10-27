package com.openhire.applicationservice.repository.mongo;

import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

@Repository
public class JobMetricsRepository {
    private final MongoTemplate mongo;

    public JobMetricsRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<Counts> counts(String tenantId, List<String> jobIds) {
        if (jobIds.isEmpty()) return List.of();
        // Count the submission workflow's authoritative records, not legacy intake documents.
        var aggregation = Aggregation.newAggregation(
                Aggregation.match(
                        Criteria.where("tenantId").is(tenantId).and("jobId").in(jobIds)),
                context -> new Document(
                        "$group",
                        new Document("_id", "$jobId")
                                .append("applicants", new Document("$sum", 1))
                                .append(
                                        "shortlisted",
                                        new Document(
                                                "$sum",
                                                new Document(
                                                        "$cond",
                                                        List.of(
                                                                new Document("$eq", List.of("$stage", "SHORTLISTED")),
                                                                1,
                                                                0))))));
        return mongo.aggregate(aggregation, "submissions", Document.class).getMappedResults().stream()
                .map(row -> new Counts(
                        row.getString("_id"),
                        ((Number) row.get("applicants")).intValue(),
                        ((Number) row.get("shortlisted")).intValue()))
                .toList();
    }

    public record Counts(String jobId, int applicants, int shortlisted) {}

    public List<SourceCount> sources(String tenantId, String jobId) {
        var aggregation = Aggregation.newAggregation(
                Aggregation.match(
                        Criteria.where("tenantId").is(tenantId).and("jobId").is(jobId)),
                context -> new Document(
                        "$group",
                        new Document("_id", new Document("$ifNull", List.of("$sourceProvider", "$source")))
                                .append("count", new Document("$sum", 1))));
        return mongo.aggregate(aggregation, "submissions", Document.class).getMappedResults().stream()
                .map(row -> new SourceCount(row.getString("_id"), ((Number) row.get("count")).longValue()))
                .toList();
    }

    public record SourceCount(String channel, long count) {}
}
