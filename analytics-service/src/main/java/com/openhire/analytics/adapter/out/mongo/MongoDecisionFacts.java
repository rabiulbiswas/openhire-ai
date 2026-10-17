package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.DecisionFacts;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.DecisionKind;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** {@code fact_decision}: human shortlist, reject and override decisions. */
@Repository
class MongoDecisionFacts implements DecisionFacts {
    static final String COLLECTION = "fact_decision";
    private final MongoTemplate mongo;

    MongoDecisionFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean add(DecisionFact d) {
        var document = new Document("_id", Docs.key(d.tenantId(), d.id()))
                .append("tenantId", d.tenantId())
                .append("id", d.id())
                .append("applicationId", d.applicationId())
                .append("jobId", d.jobId())
                .append("kind", d.kind().name())
                .append("actorType", d.actorType())
                .append("actorId", d.actorId())
                .append("at", Docs.date(d.at()))
                .append("fromStage", d.fromStage())
                .append("toStage", d.toStage())
                .append("reasonCode", d.reasonCode())
                .append("newScore", d.newScore())
                .append("undone", d.undone());
        try {
            mongo.insert(document, COLLECTION);
            return true;
        } catch (DuplicateKeyException exists) {
            return false;
        }
    }

    @Override
    public void markLatestUndone(String tenantId, String applicationId) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)
                        .and("kind")
                        .in(DecisionKind.SHORTLIST.name(), DecisionKind.REJECT.name())
                        .and("undone")
                        .is(false))
                .with(Sort.by(Sort.Direction.DESC, "at"))
                .limit(1);
        var latest = mongo.findOne(query, Document.class, COLLECTION);
        if (latest != null)
            mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(latest.get("_id"))),
                    new Update().set("undone", true),
                    COLLECTION);
    }

    @Override
    public List<DecisionFact> between(String tenantId, Instant from, Instant to) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("at")
                        .gt(Docs.date(from))
                        .lte(Docs.date(to)))
                .limit(Docs.MAX_ROWS);
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(d -> new DecisionFact(
                        d.getString("tenantId"),
                        d.getString("id"),
                        d.getString("applicationId"),
                        d.getString("jobId"),
                        DecisionKind.valueOf(d.getString("kind")),
                        d.getString("actorType"),
                        d.getString("actorId"),
                        Docs.instant(d, "at"),
                        d.getString("fromStage"),
                        d.getString("toStage"),
                        d.getString("reasonCode"),
                        d.get("newScore") instanceof Number n ? n.intValue() : null,
                        Boolean.TRUE.equals(d.get("undone"))))
                .toList();
    }
}
