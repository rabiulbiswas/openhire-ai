package com.openhire.aiworkers.matching.adapter.out.mongo;

import com.openhire.aiworkers.matching.application.port.MatchingPorts.RetryJob;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.RetryStore;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.support.Records;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Persisted retries claimed with a lease, so a restarted or second instance resumes them without duplicating work. */
@Repository
class MongoRetryStore implements RetryStore {
    private static final String COLLECTION = "matching_retries";
    private final MongoTemplate mongo;

    MongoRetryStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void schedule(String id, MatchingRequest request, Instant firstFailedAt, Instant nextAttemptAt) {
        mongo.upsert(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .setOnInsert("tenantId", request.tenantId())
                        .setOnInsert("applicationId", request.applicationId())
                        .setOnInsert("request", request.toEnvelope())
                        .setOnInsert("state", "PENDING")
                        .setOnInsert("attempt", 0)
                        .setOnInsert("firstFailedAt", Date.from(firstFailedAt))
                        .setOnInsert("nextAttemptAt", Date.from(nextAttemptAt)),
                COLLECTION);
    }

    @Override
    public Optional<RetryJob> claimNext(Instant now, Duration lease, String owner) {
        var date = Date.from(now);
        var due = Criteria.where("state")
                .is("PENDING")
                .and("nextAttemptAt")
                .lte(date)
                .andOperator(new Criteria()
                        .orOperator(
                                Criteria.where("leaseUntil").isNull(),
                                Criteria.where("leaseUntil").lte(date)));
        var row = mongo.findAndModify(
                Query.query(due).with(Sort.by("nextAttemptAt")),
                new Update().set("leaseUntil", Date.from(now.plus(lease))).set("leaseOwner", owner),
                FindAndModifyOptions.options().returnNew(true),
                Document.class,
                COLLECTION);
        if (row == null) return Optional.empty();
        return Optional.of(new RetryJob(
                row.getString("_id"),
                MatchingRequest.fromEnvelope(Records.doc(row.get("request"))),
                row.getInteger("attempt", 0),
                row.getDate("firstFailedAt").toInstant()));
    }

    @Override
    public void reschedule(String id, int attempt, Instant nextAttemptAt) {
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("attempt", attempt)
                        .set("nextAttemptAt", Date.from(nextAttemptAt))
                        .unset("leaseUntil")
                        .unset("leaseOwner"),
                COLLECTION);
    }

    @Override
    public void finish(String id, String state) {
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("state", state).unset("leaseUntil").unset("leaseOwner"),
                COLLECTION);
    }
}
