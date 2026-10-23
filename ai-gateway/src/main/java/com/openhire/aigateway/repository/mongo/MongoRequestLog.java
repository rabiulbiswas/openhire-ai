package com.openhire.aigateway.repository.mongo;

import com.openhire.aigateway.service.port.AiPorts.RequestLog;
import com.openhire.aigateway.service.port.AiPorts.RequestRecord;
import com.openhire.aigateway.service.port.AiPorts.RequestStat;
import com.openhire.support.Records;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * {@code ai_requests}: provenance rows. Each live row carries its completion event (and a budget alert when one was
 * crossed) in {@code _events}, which the embedded relay publishes to ai.events.v1.
 */
@Repository
class MongoRequestLog implements RequestLog {
    static final String COLLECTION = "ai_requests";
    private final MongoTemplate mongo;
    private final String collection;

    MongoRequestLog(MongoTemplate mongo, @Value("${openhire.outbox.collection:ai_requests}") String collection) {
        this.mongo = mongo;
        this.collection = collection;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.indexOps(collection)
                .createIndex(new Index().on("tenantId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
    }

    @Override
    public void save(RequestRecord record) {
        var p = record.provenance();
        var row = new Document("_id", p.requestId())
                .append("tenantId", record.tenantId())
                .append("task", p.task())
                .append("provider", p.provider())
                .append("model", p.model())
                .append("promptKey", p.promptKey())
                .append("promptVersion", p.promptVersion())
                .append("promptHash", record.promptHash())
                .append("latencyMs", p.latencyMs())
                .append("promptTokens", p.promptTokens())
                .append("completionTokens", p.completionTokens())
                .append("costInr", p.costInr())
                .append("cached", p.cached())
                .append("traceId", p.traceId())
                .append("createdAt", Date.from(p.createdAt()))
                .append("outcome", record.outcome())
                .append("errorCode", record.errorCode())
                .append("guardrails", Records.doc(record.guardrails()))
                .append("purpose", record.purpose());
        var events = new ArrayList<Document>();
        if ("LIVE".equals(record.purpose())) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("requestId", p.requestId());
            payload.put("task", p.task());
            payload.put("provider", p.provider());
            payload.put("model", p.model());
            payload.put("promptKey", p.promptKey());
            payload.put("promptVersion", p.promptVersion());
            payload.put("latencyMs", p.latencyMs());
            payload.put("promptTokens", p.promptTokens());
            payload.put("completionTokens", p.completionTokens());
            payload.put("costInr", p.costInr());
            payload.put("cached", p.cached());
            payload.put("outcome", record.outcome());
            payload.put("traceId", p.traceId());
            payload.put("createdAt", p.createdAt().toString());
            events.add(
                    Records.event("ai.request.completed.v1", record.tenantId(), "AiRequest", p.requestId(), payload));
        }
        if (record.alert() != null) {
            var a = record.alert();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("month", a.month());
            payload.put("usedInr", a.usedInr());
            payload.put("budgetInr", a.budgetInr());
            payload.put("percent", a.percent());
            events.add(Records.event(
                    "ai.budget.alert.v1", record.tenantId(), "AiBudget", record.tenantId() + ":" + a.month(), payload));
        }
        row.append("_events", events);
        mongo.insert(row, collection);
    }

    @Override
    public List<RequestStat> since(String tenantId, Instant from) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("purpose")
                        .is("LIVE")
                        .and("createdAt")
                        .gte(Date.from(from)))
                .limit(100_000);
        query.fields().include("task", "provider", "model", "latencyMs", "outcome", "cached", "costInr", "createdAt");
        var out = new ArrayList<RequestStat>();
        for (var d : mongo.find(query, Document.class, collection))
            out.add(new RequestStat(
                    d.getString("task"),
                    d.getString("provider"),
                    d.getString("model"),
                    d.get("latencyMs") instanceof Number n ? n.longValue() : 0,
                    d.getString("outcome"),
                    Boolean.TRUE.equals(d.getBoolean("cached")),
                    d.get("costInr") instanceof Number n ? n.doubleValue() : 0,
                    d.getDate("createdAt") == null
                            ? from
                            : d.getDate("createdAt").toInstant()));
        return out;
    }
}
