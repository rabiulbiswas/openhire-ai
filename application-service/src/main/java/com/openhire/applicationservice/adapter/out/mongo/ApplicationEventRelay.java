package com.openhire.applicationservice.adapter.out.mongo;

import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** At-least-once publication: consumers must deduplicate by envelope eventId. */
@Component
@ConditionalOnProperty(name = "openhire.outbox.relay-enabled", havingValue = "true")
public class ApplicationEventRelay {
    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public ApplicationEventRelay(MongoTemplate mongo, KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${openhire.outbox.poll-delay:1000}")
    public void publish() {
        var pending = mongo.find(
                Query.query(Criteria.where("data.pendingEvents.0").exists(true)).limit(50), ApplicationDocument.class);
        for (var document : pending) {
            for (var event : document.data().pendingEvents()) {
                try {
                    kafka.send("application.events.v1", document.id(), json.writeValueAsString(event))
                            .get(12, TimeUnit.SECONDS);
                    // Pull only the acknowledged event; never overwrite a concurrently appended event.
                    mongo.updateFirst(
                            Query.query(Criteria.where("_id")
                                    .is(document.id())
                                    .and("tenantId")
                                    .is(document.tenantId())),
                            new Update()
                                    .pull("data.pendingEvents", new Document("eventId", event.eventId()))
                                    .inc("revision", 1),
                            ApplicationDocument.class);
                } catch (Exception error) {
                    LoggerFactory.getLogger(getClass())
                            .warn(
                                    "Application event {} remains pending ({})",
                                    event.eventId(),
                                    error.getClass().getSimpleName());
                    if (error instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
