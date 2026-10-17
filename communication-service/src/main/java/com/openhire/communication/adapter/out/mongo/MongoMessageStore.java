package com.openhire.communication.adapter.out.mongo;

import com.openhire.communication.application.port.CommunicationPorts.MessageStore;
import com.openhire.communication.domain.Message;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
class MongoMessageStore implements MessageStore {
    private final MongoTemplate mongo;
    private final Clock clock;

    MongoMessageStore(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public boolean insert(Message message) {
        try {
            mongo.insert(message);
            return true;
        } catch (DuplicateKeyException redelivered) {
            return false;
        }
    }

    @Override
    public Optional<Message> find(String messageId) {
        return Optional.ofNullable(mongo.findById(messageId, Message.class));
    }

    @Override
    public List<Message> conversation(String tenantId, String conversationId, int limit) {
        var latest = new ArrayList<>(mongo.find(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("conversationId")
                                .is(conversationId))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(limit),
                Message.class));
        Collections.reverse(latest);
        return latest;
    }

    /** Updates only the delivery fields so the relay's pending events are never rewritten. */
    @Override
    public void updateStatus(Message message) {
        mongo.updateFirst(
                Query.query(Criteria.where("_id").is(message.id())),
                new Update()
                        .set("status", message.status())
                        .set("statusReason", message.statusReason())
                        .set("updatedAt", clock.instant()),
                Message.class);
    }
}
