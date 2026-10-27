package com.openhire.jobservice.messaging.publisher;

import com.openhire.jobservice.service.PublicationEvents;
import com.openhire.jobservice.service.port.OutboxWriter;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Repairs the job-save/outbox crash window without requiring replica-set transactions. */
@Component
public class PublicationRecovery {
    private final MongoTemplate mongo;
    private final OutboxWriter outbox;

    public PublicationRecovery(MongoTemplate mongo, OutboxWriter outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${openhire.outbox.publication-recovery-ms:5000}")
    public void recover() {
        var jobs = mongo.getCollection("jobs");
        for (var job : jobs.find(new Document("publicationPending", true)).limit(100)) {
            try {
                var at = job.getDate("publishedAt").toInstant();
                int version = ((Number) job.get("approvedVersion")).intValue();
                for (var type : new String[] {"job.approved.v1", "job.published.v1"})
                    outbox.append(
                            "job.events.v1",
                            PublicationEvents.event(
                                    type, job.getString("tenantId"), job.getString("_id"), version, at));
                jobs.updateOne(
                        new Document("_id", job.getString("_id")).append("approvedVersion", version),
                        new Document("$set", new Document("publicationPending", false)));
            } catch (RuntimeException unavailable) {
                org.slf4j.LoggerFactory.getLogger(PublicationRecovery.class)
                        .warn("Career publication event recovery remains pending");
                return;
            }
        }
    }
}
