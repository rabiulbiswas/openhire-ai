package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.LatencyFacts;
import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyFact;
import com.openhire.analytics.domain.Facts.LatencyKind;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** {@code fact_latency}: start and end times combined with {@code $min}, so arrival order does not matter. */
@Repository
class MongoLatencyFacts implements LatencyFacts {
    static final String COLLECTION = "fact_latency";
    private final MongoTemplate mongo;

    MongoLatencyFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void mark(String tenantId, LatencyKind kind, String key, Edge edge, Instant at) {
        var update = new Update()
                .setOnInsert("tenantId", tenantId)
                .setOnInsert("kind", kind.name())
                .setOnInsert("key", key)
                .min(edge == Edge.START ? "startAt" : "endAt", Docs.date(at));
        mongo.upsert(Query.query(Criteria.where("_id").is(Docs.key(tenantId, kind.name(), key))), update, COLLECTION);
    }

    @Override
    public List<LatencyFact> startedSince(String tenantId, LatencyKind kind, Instant since) {
        var criteria = Criteria.where("kind").is(kind.name()).and("startAt").gte(Docs.date(since));
        if (tenantId != null) criteria = criteria.and("tenantId").is(tenantId);
        return mongo.find(Query.query(criteria).limit(Docs.MAX_ROWS), Document.class, COLLECTION).stream()
                .map(d -> new LatencyFact(
                        d.getString("tenantId"),
                        kind,
                        d.getString("key"),
                        Docs.instant(d, "startAt"),
                        Docs.instant(d, "endAt")))
                .toList();
    }
}
