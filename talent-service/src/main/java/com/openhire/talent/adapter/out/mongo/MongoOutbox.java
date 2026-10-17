package com.openhire.talent.adapter.out.mongo;

import com.openhire.talent.application.port.TalentPorts.EventOutbox;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** One outbox document per event, in the embedded `_events` shape that the shared relay publishes and prunes. */
@Repository
class MongoOutbox implements EventOutbox {
    static final String PRODUCER = "talent-service";
    private final MongoTemplate mongo;
    private final String collection;

    MongoOutbox(MongoTemplate mongo, @Value("${openhire.outbox.collection}") String collection) {
        this.mongo = mongo;
        this.collection = collection;
    }

    @Override
    public void publish(
            String tenantId, String type, String aggregateType, String aggregateId, Map<String, Object> payload) {
        var event = new Document("eventId", UUID.randomUUID().toString())
                .append("producer", PRODUCER)
                .append("eventType", type)
                .append("eventVersion", 1)
                .append("occurredAt", Instant.now().toString())
                .append("tenantId", tenantId)
                .append("aggregateType", aggregateType)
                .append("aggregateId", aggregateId)
                .append("correlationId", aggregateId)
                .append("payload", new Document(payload));
        mongo.insert(
                new Document("_id", UUID.randomUUID().toString())
                        .append("tenantId", tenantId)
                        .append("createdAt", new Date())
                        .append("_events", List.of(event)),
                collection);
    }

    @Override
    public void purgeDelivered(Instant before) {
        mongo.remove(
                Query.query(Criteria.where("_events").size(0).and("createdAt").lt(Date.from(before))), collection);
    }
}
