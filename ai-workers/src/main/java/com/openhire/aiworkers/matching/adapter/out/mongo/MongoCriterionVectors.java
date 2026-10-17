package com.openhire.aiworkers.matching.adapter.out.mongo;

import com.openhire.aiworkers.matching.application.port.MatchingPorts.CriterionVectors;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoCriterionVectors implements CriterionVectors {
    private static final String COLLECTION = "criterion_vectors";
    private final MongoTemplate mongo;

    MongoCriterionVectors(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<double[]> find(String tenantId, String textHash) {
        var row = mongo.findOne(
                Query.query(Criteria.where("_id").is(id(tenantId, textHash))), Document.class, COLLECTION);
        return row == null ? Optional.empty() : Optional.of(MongoChunkStore.vector(row.get("vector")));
    }

    @Override
    public void put(String tenantId, String textHash, double[] vector) {
        var values = new ArrayList<Double>(vector.length);
        for (double value : vector) values.add(value);
        mongo.upsert(
                Query.query(Criteria.where("_id").is(id(tenantId, textHash))),
                new Update()
                        .set("tenantId", tenantId)
                        .set("textHash", textHash)
                        .set("vector", values)
                        .set("createdAt", Instant.now()),
                COLLECTION);
    }

    private static String id(String tenantId, String textHash) {
        return tenantId + ":" + textHash;
    }
}
