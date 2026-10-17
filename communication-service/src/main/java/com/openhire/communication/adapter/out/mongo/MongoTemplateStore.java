package com.openhire.communication.adapter.out.mongo;

import com.openhire.communication.application.port.CommunicationPorts.TemplateStore;
import com.openhire.communication.domain.MessageTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoTemplateStore implements TemplateStore {
    private final MongoTemplate mongo;

    MongoTemplateStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<MessageTemplate> find(String tenantId, String key) {
        return Optional.ofNullable(mongo.findById(MessageTemplate.idFor(tenantId, key), MessageTemplate.class));
    }

    @Override
    public List<MessageTemplate> all(String tenantId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId)).with(Sort.by("name")), MessageTemplate.class);
    }

    @Override
    public MessageTemplate save(MessageTemplate template) {
        return mongo.save(template);
    }

    @Override
    public void insertIfAbsent(MessageTemplate template) {
        try {
            mongo.insert(template);
        } catch (DuplicateKeyException exists) {
            // Another request seeded it first.
        }
    }
}
