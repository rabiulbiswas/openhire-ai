package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.Replay;
import com.openhire.analytics.application.port.AnalyticsPorts.ReplayLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** {@code dlq_replays}: one row per replayed dead letter; the unique {@code _id} is the exactly-once fence. */
@Repository
class MongoReplayLog implements ReplayLog {
    static final String COLLECTION = "dlq_replays";
    private final MongoTemplate mongo;

    MongoReplayLog(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Replay> find(String id) {
        return Optional.ofNullable(mongo.findById(id, Document.class, COLLECTION))
                .map(MongoReplayLog::read);
    }

    @Override
    public List<Replay> findAll(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        return mongo.find(Query.query(Criteria.where("_id").in(ids)), Document.class, COLLECTION).stream()
                .map(MongoReplayLog::read)
                .toList();
    }

    @Override
    public boolean insert(Replay replay) {
        try {
            mongo.insert(write(replay), COLLECTION);
            return true;
        } catch (DuplicateKeyException exists) {
            return false;
        }
    }

    @Override
    public void update(Replay replay) {
        mongo.save(write(replay), COLLECTION);
    }

    @Override
    public void delete(String id) {
        mongo.remove(Query.query(Criteria.where("_id").is(id)), COLLECTION);
    }

    @Override
    public List<Instant> since(String tenantId, Instant since) {
        var query = Query.query(
                Criteria.where("tenantId").is(tenantId).and("replayedAt").gte(Docs.date(since)));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(d -> Docs.instant(d, "replayedAt"))
                .toList();
    }

    private static Document write(Replay r) {
        return new Document("_id", r.id())
                .append("tenantId", r.tenantId())
                .append("originalTopic", r.originalTopic())
                .append("reason", r.reason())
                .append("replayedBy", r.replayedBy())
                .append("replayedAt", Docs.date(r.replayedAt()))
                .append("count", r.count())
                .append("forced", r.forced());
    }

    private static Replay read(Document d) {
        return new Replay(
                d.getString("_id"),
                d.getString("tenantId"),
                d.getString("originalTopic"),
                d.getString("reason"),
                d.getString("replayedBy"),
                Docs.instant(d, "replayedAt"),
                (int) Docs.number(d, "count"),
                Boolean.TRUE.equals(d.get("forced")));
    }
}
