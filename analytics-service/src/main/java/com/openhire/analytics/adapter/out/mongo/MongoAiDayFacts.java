package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.AiDayFacts;
import com.openhire.analytics.domain.Facts;
import com.openhire.analytics.domain.Facts.AiDayFact;
import com.openhire.analytics.domain.Facts.AiSample;
import java.time.LocalDate;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** {@code fact_ai_day}: counters per tenant, day, task and model, incremented atomically with a latency histogram. */
@Repository
class MongoAiDayFacts implements AiDayFacts {
    static final String COLLECTION = "fact_ai_day";
    private final MongoTemplate mongo;

    MongoAiDayFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void add(AiSample s) {
        var update = new Update()
                .setOnInsert("tenantId", s.tenantId())
                .setOnInsert("day", s.day().toString())
                .setOnInsert("task", s.task())
                .setOnInsert("model", s.model())
                .inc("requests", 1L)
                .inc("errors", s.error() ? 1L : 0L)
                .inc("promptTokens", s.promptTokens())
                .inc("completionTokens", s.completionTokens())
                .inc("costInr", s.costInr())
                .inc("latencyMsSum", s.latencyMs())
                .inc("b" + Facts.bucket(s.latencyMs()), 1L);
        var key = Docs.key(s.tenantId(), s.day().toString(), s.task(), s.model());
        mongo.upsert(Query.query(Criteria.where("_id").is(key)), update, COLLECTION);
    }

    @Override
    public List<AiDayFact> between(String tenantId, LocalDate from, LocalDate to) {
        var query = Query.query(Criteria.where("tenantId")
                .is(tenantId)
                .and("day")
                .gte(from.toString())
                .lte(to.toString()));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(d -> {
                    var buckets = new long[Facts.LATENCY_BOUNDS.length + 1];
                    for (int i = 0; i < buckets.length; i++) buckets[i] = Docs.number(d, "b" + i);
                    return new AiDayFact(
                            d.getString("tenantId"),
                            LocalDate.parse(d.getString("day")),
                            d.getString("task"),
                            d.getString("model"),
                            Docs.number(d, "requests"),
                            Docs.number(d, "errors"),
                            Docs.number(d, "promptTokens"),
                            Docs.number(d, "completionTokens"),
                            d.get("costInr") instanceof Number n ? n.doubleValue() : 0,
                            Docs.number(d, "latencyMsSum"),
                            buckets);
                })
                .toList();
    }
}
