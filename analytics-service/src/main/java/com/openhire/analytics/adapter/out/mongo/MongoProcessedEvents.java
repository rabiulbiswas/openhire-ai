package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.ProcessedEvents;
import java.time.Instant;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoProcessedEvents implements ProcessedEvents {
    static final String COLLECTION = "processed_events";
    private final MongoTemplate mongo;

    MongoProcessedEvents(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean claim(String consumer, String eventId, Instant at) {
        try {
            mongo.insert(new Document("_id", consumer + ":" + eventId).append("at", Docs.date(at)), COLLECTION);
            return true;
        } catch (DuplicateKeyException seen) {
            return false;
        }
    }

    @Override
    public void release(String consumer, String eventId) {
        mongo.remove(Query.query(Criteria.where("_id").is(consumer + ":" + eventId)), COLLECTION);
    }
}
