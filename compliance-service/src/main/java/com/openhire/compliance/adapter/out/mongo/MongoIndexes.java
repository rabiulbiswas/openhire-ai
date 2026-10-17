package com.openhire.compliance.adapter.out.mongo;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.concurrent.TimeUnit;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoIndexes {
    private static final Logger LOG = LoggerFactory.getLogger(MongoIndexes.class);
    private final MongoTemplate mongo;
    private final String outbox;

    MongoIndexes(MongoTemplate mongo, @Value("${openhire.outbox.collection}") String outbox) {
        this.mongo = mongo;
        this.outbox = outbox;
    }

    @EventListener(ApplicationReadyEvent.class)
    void create() {
        var audit = MongoAuditStore.COLLECTION;
        index(
                audit,
                Indexes.ascending("tenantId", "seq"),
                new IndexOptions().unique(true).name(MongoAuditStore.SEQ_INDEX));
        index(
                audit,
                Indexes.ascending("eventId"),
                new IndexOptions().unique(true).name(MongoAuditStore.EVENT_INDEX));
        index(audit, Indexes.ascending("tenantId", "resource.id", "at"), new IndexOptions());
        index(audit, Indexes.ascending("tenantId", "at", "seq"), new IndexOptions());
        index("audit_archive", Indexes.ascending("tenantId", "archivedAt"), new IndexOptions());
        index(audit, Indexes.ascending("tenantId", "action", "at"), new IndexOptions());
        index(audit, Indexes.ascending("tenantId", "candidateId", "at"), new IndexOptions());
        index(audit, Indexes.ascending("tenantId", "applicationId", "at"), new IndexOptions());
        index(MongoActivityStore.COLLECTION, Indexes.ascending("tenantId", "candidateId"), new IndexOptions());
        index(MongoActivityStore.COLLECTION, Indexes.ascending("tenantId", "jobId"), new IndexOptions());
        index(MongoActivityStore.COLLECTION, Indexes.ascending("tenantId", "lastActivityAt"), new IndexOptions());
        index(MongoDecisionStore.COLLECTION, Indexes.ascending("tenantId", "applicationId", "at"), new IndexOptions());
        index(MongoDecisionStore.COLLECTION, Indexes.ascending("tenantId", "candidateId", "at"), new IndexOptions());
        index(MongoPrivacyStore.REQUESTS, Indexes.ascending("tenantId", "candidateId"), new IndexOptions());
        index(MongoPrivacyStore.REQUESTS, Indexes.ascending("tenantId", "status", "createdAt"), new IndexOptions());
        index(MongoPrivacyStore.REQUESTS, Indexes.ascending("status", "createdAt"), new IndexOptions());
        index(
                MongoPrivacyStore.EXPORTS,
                Indexes.ascending("purgeAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
        index(
                MongoPrivacyStore.EXPORTS,
                Indexes.ascending("createdAt"),
                new IndexOptions().expireAfter(120L, TimeUnit.DAYS));
        index("consent_texts", Indexes.ascending("tenantId", "purpose", "status"), new IndexOptions());
        index("legal_holds", Indexes.ascending("tenantId", "status"), new IndexOptions());
        index("self_identification", Indexes.ascending("tenantId", "applicationId"), new IndexOptions());
        index("self_identification", Indexes.ascending("tenantId", "candidateId"), new IndexOptions());
        index(outbox, Indexes.ascending("createdAt"), new IndexOptions().expireAfter(3L, TimeUnit.DAYS));
    }

    private void index(String collection, Bson keys, IndexOptions options) {
        try {
            mongo.getCollection(collection).createIndex(keys, options);
        } catch (RuntimeException e) {
            LOG.error("Index on {} could not be created: {}", collection, e.toString());
        }
    }
}
