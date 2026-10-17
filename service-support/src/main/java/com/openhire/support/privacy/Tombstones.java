package com.openhire.support.privacy;

import java.util.Date;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Remembers which candidates were erased, in the service's own database. Event consumers check it first, so a late
 * event for an erased candidate cannot bring their data back. Holds only the opaque candidate id and the request id.
 */
@Component
public class Tombstones {
    private static final String COLLECTION = "erased_candidates";
    private final MongoTemplate mongo;

    Tombstones(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void mark(String tenantId, String candidateId, String requestId) {
        var id = tenantId + "|" + candidateId;
        mongo.getCollection(COLLECTION)
                .replaceOne(
                        new Document("_id", id),
                        new Document("_id", id)
                                .append("tenantId", tenantId)
                                .append("candidateId", candidateId)
                                .append("requestId", requestId)
                                .append("at", new Date()),
                        new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    public boolean isErased(String tenantId, String candidateId) {
        return candidateId != null
                && mongo.exists(Query.query(Criteria.where("_id").is(tenantId + "|" + candidateId)), COLLECTION);
    }
}
