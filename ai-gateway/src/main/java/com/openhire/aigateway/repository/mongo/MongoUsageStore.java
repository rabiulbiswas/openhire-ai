package com.openhire.aigateway.repository.mongo;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.service.port.AiPorts.TaskUsage;
import com.openhire.aigateway.service.port.AiPorts.Usage;
import com.openhire.aigateway.service.port.AiPorts.UsageStore;
import com.openhire.support.Records;
import java.util.LinkedHashMap;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** {@code ai_usage}: one document per tenant and month, updated with atomic increments. */
@Repository
class MongoUsageStore implements UsageStore {
    private static final String COLLECTION = "ai_usage";
    private final MongoTemplate mongo;

    MongoUsageStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Usage add(
            String tenantId,
            String month,
            AiTask task,
            int promptTokens,
            int completionTokens,
            double costInr,
            long computeMs) {
        var prefix = "byTask." + task.name() + ".";
        var update = new Update()
                .set("tenantId", tenantId)
                .set("month", month)
                .inc("costInr", costInr)
                .inc("promptTokens", promptTokens)
                .inc("completionTokens", completionTokens)
                .inc("requests", 1)
                .inc("computeMs", computeMs)
                .inc(prefix + "costInr", costInr)
                .inc(prefix + "promptTokens", promptTokens)
                .inc(prefix + "completionTokens", completionTokens)
                .inc(prefix + "requests", 1);
        var row = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(tenantId + ":" + month)),
                update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                Document.class,
                COLLECTION);
        return usage(row);
    }

    @Override
    public Usage get(String tenantId, String month) {
        return usage(mongo.findById(tenantId + ":" + month, Document.class, COLLECTION));
    }

    private static Usage usage(Document row) {
        if (row == null) return Usage.none();
        var byTask = new LinkedHashMap<String, TaskUsage>();
        var tasks = Records.doc(row.get("byTask"));
        tasks.forEach((key, value) -> {
            var t = Records.doc(value);
            byTask.put(
                    key,
                    new TaskUsage(
                            num(t, "costInr"), (long) num(t, "promptTokens"), (long) num(t, "completionTokens"), (long)
                                    num(t, "requests")));
        });
        return new Usage(
                num(row, "costInr"),
                (long) num(row, "promptTokens"),
                (long) num(row, "completionTokens"),
                (long) num(row, "requests"),
                (long) num(row, "computeMs"),
                byTask);
    }

    private static double num(Document doc, String key) {
        return doc.get(key) instanceof Number n ? n.doubleValue() : 0;
    }
}
