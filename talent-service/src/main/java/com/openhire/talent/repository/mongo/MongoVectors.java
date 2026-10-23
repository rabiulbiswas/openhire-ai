package com.openhire.talent.repository.mongo;

import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.service.port.TalentPorts.VectorStore;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Vectors live in MongoDB and are searched by exact cosine in the service; a vector database comes with scale. */
@Repository
class MongoVectors implements VectorStore {
    static final String COLLECTION = "talent_profile_vectors";
    private final MongoTemplate mongo;

    MongoVectors(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(String tenantId, String candidateId, float[] vector) {
        var values = new ArrayList<Double>(vector.length);
        for (float value : vector) values.add((double) value);
        mongo.upsert(
                Query.query(Criteria.where("_id").is(TalentProfile.idFor(tenantId, candidateId))),
                new Update()
                        .set("tenantId", tenantId)
                        .set("candidateId", candidateId)
                        .set("dimensions", vector.length)
                        .set("vector", values)
                        .set("updatedAt", new Date()),
                COLLECTION);
    }

    @Override
    public void delete(String tenantId, String candidateId) {
        mongo.remove(Query.query(Criteria.where("_id").is(TalentProfile.idFor(tenantId, candidateId))), COLLECTION);
    }

    @Override
    public Map<String, float[]> all(String tenantId) {
        var result = new HashMap<String, float[]>();
        for (var row : mongo.find(Query.query(Criteria.where("tenantId").is(tenantId)), Document.class, COLLECTION)) {
            if (!(row.get("vector") instanceof List<?> list)) continue;
            var vector = new float[list.size()];
            for (int i = 0; i < vector.length; i++) vector[i] = ((Number) list.get(i)).floatValue();
            result.put(row.getString("candidateId"), vector);
        }
        return result;
    }
}
