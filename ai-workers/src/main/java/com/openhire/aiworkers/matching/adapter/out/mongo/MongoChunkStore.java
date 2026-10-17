package com.openhire.aiworkers.matching.adapter.out.mongo;

import com.openhire.aiworkers.matching.application.port.MatchingPorts.ChunkStore;
import com.openhire.aiworkers.matching.domain.Chunk;
import com.openhire.aiworkers.matching.domain.StoredChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoChunkStore implements ChunkStore {
    static final String COLLECTION = "resume_chunks";
    private final MongoTemplate mongo;

    MongoChunkStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public List<String> chunkIds(String tenantId, String resumeId) {
        var query = Query.query(
                        Criteria.where("tenantId").is(tenantId).and("resumeId").is(resumeId))
                .with(Sort.by("index"));
        query.fields().include("chunkId");
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(row -> row.getString("chunkId"))
                .toList();
    }

    @Override
    public void replace(
            String tenantId, String candidateId, String applicationId, String resumeId, List<StoredChunk> chunks) {
        mongo.remove(
                Query.query(
                        Criteria.where("tenantId").is(tenantId).and("resumeId").is(resumeId)),
                COLLECTION);
        var rows = new ArrayList<Document>();
        var now = Instant.now();
        for (var stored : chunks) {
            var chunk = stored.chunk();
            var vector = new ArrayList<Double>(stored.vector().length);
            for (double value : stored.vector()) vector.add(value);
            rows.add(new Document("_id", chunk.id())
                    .append("tenantId", tenantId)
                    .append("candidateId", candidateId)
                    .append("applicationId", applicationId)
                    .append("resumeId", resumeId)
                    .append("chunkId", chunk.id())
                    .append("index", chunk.index())
                    .append("section", chunk.section())
                    .append("text", chunk.text())
                    .append("vector", vector)
                    .append("createdAt", now));
        }
        if (!rows.isEmpty()) mongo.insert(rows, COLLECTION);
    }

    @Override
    public List<StoredChunk> chunks(String tenantId, String candidateId, String resumeId) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)
                        .and("resumeId")
                        .is(resumeId))
                .with(Sort.by("index"));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(row -> new StoredChunk(
                        new Chunk(
                                row.getString("chunkId"),
                                row.getInteger("index", 0),
                                row.getString("section"),
                                row.getString("text")),
                        vector(row.get("vector"))))
                .toList();
    }

    static double[] vector(Object value) {
        if (!(value instanceof List<?> numbers)) return new double[0];
        var vector = new double[numbers.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = ((Number) numbers.get(i)).doubleValue();
        return vector;
    }
}
