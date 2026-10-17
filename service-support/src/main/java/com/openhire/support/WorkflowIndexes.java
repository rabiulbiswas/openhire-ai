package com.openhire.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Indexed pollers and tenant-scoped reads; unique _id is MongoDB's built-in command dedupe fence. */
@Component
public class WorkflowIndexes {
    private final MongoTemplate mongo;
    private final String collection;

    public WorkflowIndexes(MongoTemplate mongo, @Value("${openhire.outbox.collection}") String collection) {
        this.mongo = mongo;
        this.collection = collection;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (collection.equals("submissions")) {
            index("submissions", "tenantId", "jobId", "submittedAt", "_id");
            index("submissions", "tenantId", "stage", "submittedAt");
            index("submissions", "tenantId", "actorId", "submittedAt");
            index("submissions", "tenantId", "candidateId");
            index("submissions", "processingState", "nextAttempt", "leaseUntil");
            index("submissions", "projectionDirty", "candidateId");
            index("imports", "state", "finished");
            index("import_rows", "tenantId", "importId", "state", "leaseUntil");
            index("agency_assignments", "tenantId", "actorId", "active");
        } else if (collection.equals("resumes")) index("resumes", "status", "leaseUntil", "attempts");
        else if (collection.equals("candidates")) {
            index("candidates", "tenantId", "normalizedName");
            index("candidates", "tenantId", "phone");
            index("candidate_merges", "status", "locksReleased", "runUntil");
        }
    }

    private void index(String name, String... fields) {
        var index = new Index();
        for (String field : fields) index.on(field, Direction.ASC);
        mongo.indexOps(name).createIndex(index);
    }
}
