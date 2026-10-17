package com.openhire.compliance.adapter.out.mongo;

import com.openhire.compliance.application.port.CompliancePorts.Events;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Writes events to the embedded outbox (one document per event); the shared relay publishes them. */
@Component
class MongoEvents implements Events {
    private final MongoTemplate mongo;
    private final String collection;

    MongoEvents(MongoTemplate mongo, @Value("${openhire.outbox.collection}") String collection) {
        this.mongo = mongo;
        this.collection = collection;
    }

    @Override
    public void publish(
            String tenantId, String aggregateType, String aggregateId, String eventType, Map<String, Object> payload) {
        var eventId = UUID.randomUUID().toString();
        var event = new Document("eventId", eventId)
                .append("producer", "compliance-service")
                .append("eventType", eventType)
                .append("eventVersion", 1)
                .append("occurredAt", Instant.now().toString())
                .append("tenantId", tenantId)
                .append("aggregateType", aggregateType)
                .append("aggregateId", aggregateId)
                .append("correlationId", aggregateId)
                .append("payload", payload);
        mongo.getCollection(collection)
                .insertOne(new Document("_id", eventId)
                        .append("createdAt", new Date())
                        .append("_events", List.of(event)));
    }
}
