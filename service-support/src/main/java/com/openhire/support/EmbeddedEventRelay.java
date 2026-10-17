package com.openhire.support;

import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = {"openhire.outbox.collection", "openhire.outbox.relay-enabled"})
public class EmbeddedEventRelay {
    private final MongoTemplate mongo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String collection;
    private final String topic;

    public EmbeddedEventRelay(
            MongoTemplate mongo,
            KafkaTemplate<String, String> kafka,
            ObjectMapper json,
            @Value("${openhire.outbox.collection}") String collection,
            @Value("${openhire.outbox.topic}") String topic) {
        this.mongo = mongo;
        this.kafka = kafka;
        this.json = json;
        this.collection = collection;
        this.topic = topic;
    }

    @Scheduled(fixedDelay = 1000)
    public void publish() {
        for (var row : mongo.find(
                Query.query(Criteria.where("_events.0").exists(true)).limit(50), Document.class, collection)) {
            for (var event : Records.documents(row.get("_events")))
                try {
                    kafka.send(topic, row.getString("_id"), json.writeValueAsString(event))
                            .get(12, TimeUnit.SECONDS);
                    mongo.updateFirst(
                            Query.query(Criteria.where("_id").is(row.get("_id"))),
                            new Update().pull("_events", new Document("eventId", event.getString("eventId"))),
                            collection);
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn(
                                    "Pending event {} ({})",
                                    event.getString("eventId"),
                                    e.getClass().getSimpleName());
                    if (e instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                    return;
                }
        }
    }
}
