package com.openhire.communication.repository.mongo;

import com.openhire.communication.domain.Conversation;
import com.openhire.communication.service.port.CommunicationPorts.ConversationStore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoConversationStore implements ConversationStore {
    private final MongoTemplate mongo;

    MongoConversationStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Conversation> find(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("_id").is(id).and("tenantId").is(tenantId)), Conversation.class));
    }

    @Override
    public Conversation save(Conversation conversation) {
        return mongo.save(conversation);
    }

    @Override
    public List<Conversation> recent(String tenantId, int limit) {
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId))
                        .with(Sort.by(Sort.Direction.DESC, "lastMessageAt"))
                        .limit(limit),
                Conversation.class);
    }
}
