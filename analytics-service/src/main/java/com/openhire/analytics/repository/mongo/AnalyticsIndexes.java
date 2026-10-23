package com.openhire.analytics.repository.mongo;

import java.time.Duration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Query indexes and the TTLs that bound the idempotency ledger and the health series. */
@Component
class AnalyticsIndexes {
    private final MongoTemplate mongo;

    AnalyticsIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        index(MongoApplicationFacts.COLLECTION, "tenantId", "lastEventAt");
        index(MongoApplicationFacts.COLLECTION, "tenantId", "jobId", "lastEventAt");
        index(MongoAssessmentFacts.COLLECTION, "tenantId", "applicationId");
        index(MongoAssessmentFacts.COLLECTION, "tenantId", "completedAt");
        index(MongoDecisionFacts.COLLECTION, "tenantId", "at");
        index(MongoDecisionFacts.COLLECTION, "tenantId", "applicationId", "at");
        index(MongoOfferFacts.COLLECTION, "tenantId", "lastEventAt");
        index(MongoAiDayFacts.COLLECTION, "tenantId", "day");
        index(MongoLatencyFacts.COLLECTION, "kind", "startAt");
        index(MongoLatencyFacts.COLLECTION, "tenantId", "kind", "startAt");
        index(MongoHealthStore.COLLECTION, "service", "at");
        index(MongoReplayLog.COLLECTION, "tenantId", "replayedAt");
        ttl(MongoHealthStore.COLLECTION, Duration.ofHours(25));
        ttl(MongoProcessedEvents.COLLECTION, Duration.ofDays(400));
    }

    private void index(String collection, String... fields) {
        var index = new Index();
        for (String field : fields) index.on(field, Direction.ASC);
        mongo.indexOps(collection).createIndex(index);
    }

    private void ttl(String collection, Duration after) {
        mongo.indexOps(collection)
                .createIndex(new Index().on("at", Direction.ASC).expire(after));
    }
}
