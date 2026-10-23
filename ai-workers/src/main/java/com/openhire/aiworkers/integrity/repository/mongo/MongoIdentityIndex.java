package com.openhire.aiworkers.integrity.repository.mongo;

import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.IdentityEntry;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.IdentityIndex;
import com.openhire.aiworkers.matching.domain.Hashes;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Hashed contact values per application; plain email, phone and names are never stored. */
@Repository
class MongoIdentityIndex implements IdentityIndex {
    private static final String COLLECTION = "identity_index";
    private final MongoTemplate mongo;

    MongoIdentityIndex(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void register(IdentityEntry entry) {
        var id = Hashes.sha256(entry.tenantId(), entry.applicationId(), entry.kind(), entry.valueHash());
        mongo.upsert(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("tenantId", entry.tenantId())
                        .set("applicationId", entry.applicationId())
                        .set("candidateId", entry.candidateId())
                        .set("kind", entry.kind())
                        .set("valueHash", entry.valueHash())
                        .set("nameTokens", List.copyOf(entry.nameTokens()))
                        .set("updatedAt", new Date()),
                COLLECTION);
    }

    @Override
    public List<IdentityEntry> others(String tenantId, String kind, String valueHash, String candidateId) {
        return mongo
                .find(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("kind")
                                .is(kind)
                                .and("valueHash")
                                .is(valueHash)
                                .and("candidateId")
                                .ne(candidateId)),
                        Document.class,
                        COLLECTION)
                .stream()
                .map(row -> new IdentityEntry(
                        tenantId,
                        row.getString("applicationId"),
                        row.getString("candidateId"),
                        kind,
                        valueHash,
                        new HashSet<String>(row.getList("nameTokens", String.class))))
                .toList();
    }
}
