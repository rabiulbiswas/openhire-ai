package com.openhire.communication.repository.mongo;

import com.openhire.communication.domain.StaffNotification;
import com.openhire.communication.service.port.CommunicationPorts.NotificationStore;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
class MongoNotificationStore implements NotificationStore {
    private final MongoTemplate mongo;
    private final Clock clock;

    MongoNotificationStore(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public boolean insert(StaffNotification notification) {
        try {
            mongo.insert(notification);
            return true;
        } catch (DuplicateKeyException redelivered) {
            return false;
        }
    }

    @Override
    public List<StaffNotification> list(String tenantId, Collection<String> recipients, String filter, int limit) {
        var criteria = Criteria.where("tenantId").is(tenantId).and("recipient").in(recipients);
        if ("unread".equals(filter)) criteria = criteria.and("readAt").is(null);
        if ("mentions".equals(filter)) criteria = criteria.and("mention").is(true);
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .limit(limit),
                StaffNotification.class);
    }

    @Override
    public long unread(String tenantId, Collection<String> recipients) {
        return mongo.count(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("recipient")
                        .in(recipients)
                        .and("readAt")
                        .is(null)),
                StaffNotification.class);
    }

    @Override
    public boolean markRead(String tenantId, String id, Collection<String> recipients) {
        var matched = mongo.updateFirst(
                        Query.query(Criteria.where("_id")
                                .is(id)
                                .and("tenantId")
                                .is(tenantId)
                                .and("recipient")
                                .in(recipients)),
                        new Update().set("readAt", clock.instant()),
                        StaffNotification.class)
                .getMatchedCount();
        return matched > 0;
    }

    @Override
    public void markAllRead(String tenantId, Collection<String> recipients) {
        mongo.updateMulti(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("recipient")
                        .in(recipients)
                        .and("readAt")
                        .is(null)),
                new Update().set("readAt", clock.instant()),
                StaffNotification.class);
    }
}
