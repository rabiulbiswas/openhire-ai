package com.openhire.aiworkers.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
class AiWorkersIndexes {
    private final MongoTemplate mongo;

    AiWorkersIndexes(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        index("assessments", "tenantId", "commandEventId");
        index("assessments", "tenantId", "applicationId", "jobVersion", "current");
        index("assessments", "tenantId", "applicationId", "resumeId", "jobVersion", "rubricHash", "status");
        index("resume_chunks", "tenantId", "resumeId");
        index("resume_chunks", "tenantId", "candidateId", "resumeId");
        index("matching_retries", "state", "nextAttemptAt", "leaseUntil");
        index("integrity_signals", "tenantId", "applicationId");
        index("identity_index", "tenantId", "kind", "valueHash");
    }

    private void index(String collection, String... fields) {
        var index = new Index();
        for (String field : fields) index.on(field, Direction.ASC);
        mongo.indexOps(collection).createIndex(index);
    }
}
