package com.openhire.aigateway.adapter.out.mongo;

import com.openhire.aigateway.application.port.AiPorts.PromptStore;
import com.openhire.aigateway.domain.PromptVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoPromptStore implements PromptStore {
    private final MongoTemplate mongo;

    MongoPromptStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---- prompts ----

    @Override
    public void insertIfAbsent(PromptVersion prompt) {
        try {
            mongo.insert(prompt);
        } catch (DuplicateKeyException exists) {
            // Already seeded, possibly edited since; leave it alone.
        }
    }

    @Override
    public List<PromptVersion> list(String tenantId) {
        return mongo.find(Query.query(Criteria.where("tenantId").is(tenantId)), PromptVersion.class);
    }

    @Override
    public List<PromptVersion> versions(String tenantId, String key) {
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId).and("key").is(key)), PromptVersion.class);
    }

    @Override
    public Optional<PromptVersion> find(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(tenantId + ":" + id)),
                PromptVersion.class));
    }

    @Override
    public PromptVersion save(PromptVersion prompt) {
        return mongo.save(prompt);
    }
}
