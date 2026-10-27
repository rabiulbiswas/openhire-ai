package com.openhire.applicationservice.repository.mongo;

import com.openhire.applicationservice.dto.PartnerPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class PartnerRepository {
    private final MongoTemplate mongo;

    public PartnerRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean assigned(String tenant, String actor, String job) {
        return mongo.exists(
                Query.query(Criteria.where("tenantId")
                        .is(tenant)
                        .and("actorId")
                        .is(actor)
                        .and("jobId")
                        .is(job)
                        .and("active")
                        .is(true)),
                "agency_assignments");
    }

    public PartnerPage<Document> jobs(String tenant, String actor, String after, String before, int limit) {
        return page(
                "agency_assignments",
                Criteria.where("tenantId")
                        .is(tenant)
                        .and("actorId")
                        .is(actor)
                        .and("active")
                        .is(true),
                false,
                after,
                before,
                limit);
    }

    public PartnerPage<Document> submissions(String tenant, String actor, String after, String before, int limit) {
        return page(
                "submissions",
                Criteria.where("tenantId")
                        .is(tenant)
                        .and("actorId")
                        .is(actor)
                        .and("source")
                        .is("AGENCY"),
                true,
                after,
                before,
                limit);
    }

    public PartnerPage<Document> employeeSubmissions(
            String tenant, String actor, String after, String before, int limit) {
        return page(
                "submissions",
                Criteria.where("tenantId")
                        .is(tenant)
                        .and("actorId")
                        .is(actor)
                        .and("source")
                        .in("REFERRAL", "INTERNAL"),
                true,
                after,
                before,
                limit);
    }

    private PartnerPage<Document> page(
            String collection, Criteria scope, boolean chronological, String after, String before, int limit) {
        if (limit < 1
                || limit > 100
                || after.length() > 512
                || before.length() > 512
                || (!after.isBlank() && !before.isBlank()))
            throw new IllegalArgumentException("Invalid partner pagination");
        boolean reverse = !before.isBlank();
        String cursor = reverse ? before : after;
        var fetched = find(collection, scope, chronological, cursor, reverse, limit + 1);
        var rows = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
        if (reverse) Collections.reverse(rows);
        boolean previous = false, more = false;
        if (!rows.isEmpty()) {
            previous = reverse
                    ? fetched.size() > limit
                    : !cursor.isBlank()
                            && !find(collection, scope, chronological, cursor(rows.getFirst(), chronological), true, 1)
                                    .isEmpty();
            more = reverse
                    ? !find(collection, scope, chronological, cursor(rows.getLast(), chronological), false, 1)
                            .isEmpty()
                    : fetched.size() > limit;
        }
        return new PartnerPage<>(
                rows,
                more ? cursor(rows.getLast(), chronological) : null,
                more,
                previous ? cursor(rows.getFirst(), chronological) : null,
                previous,
                limit);
    }

    private List<Document> find(
            String collection, Criteria scope, boolean chronological, String cursor, boolean reverse, int limit) {
        var filters = new ArrayList<Criteria>();
        filters.add(scope);
        if (!cursor.isBlank()) {
            try {
                var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2 || parts[1].isBlank() || parts[1].length() > 200)
                    throw new IllegalArgumentException();
                if (chronological) {
                    Instant.parse(parts[0]);
                    filters.add(new Criteria()
                            .orOperator(
                                    reverse
                                            ? Criteria.where("submittedAt").gt(parts[0])
                                            : Criteria.where("submittedAt").lt(parts[0]),
                                    new Criteria()
                                            .andOperator(
                                                    Criteria.where("submittedAt")
                                                            .is(parts[0]),
                                                    reverse
                                                            ? Criteria.where("_id")
                                                                    .gt(parts[1])
                                                            : Criteria.where("_id")
                                                                    .lt(parts[1]))));
                } else
                    filters.add(
                            reverse
                                    ? Criteria.where("_id").lt(parts[1])
                                    : Criteria.where("_id").gt(parts[1]));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid partner cursor");
            }
        }
        var direction = chronological == reverse ? Sort.Direction.ASC : Sort.Direction.DESC;
        var sort = chronological ? Sort.by(direction, "submittedAt", "_id") : Sort.by(direction, "_id");
        var query = Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new)))
                .with(sort)
                .limit(limit);
        if (chronological) query.fields().include("name", "jobTitle", "stage", "submittedAt");
        return mongo.find(query, Document.class, collection);
    }

    private String cursor(Document row, boolean chronological) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(((chronological ? row.getString("submittedAt") : "") + "|" + row.getString("_id"))
                        .getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Document> counts(String tenant, String actor, List<String> jobs) {
        if (jobs.isEmpty()) return Map.of();
        var aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("tenantId")
                        .is(tenant)
                        .and("actorId")
                        .is(actor)
                        .and("source")
                        .is("AGENCY")
                        .and("jobId")
                        .in(jobs)),
                context -> new Document(
                        "$group",
                        new Document("_id", "$jobId")
                                .append("submitted", new Document("$sum", 1))
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
        var result = new HashMap<String, Document>();
        mongo.aggregate(aggregation, "submissions", Document.class)
                .getMappedResults()
                .forEach(row -> result.put(row.getString("_id"), row));
        return result;
    }
}
