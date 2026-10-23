package com.openhire.communication.repository.mongo;

import com.openhire.communication.domain.NotificationPreferences;
import com.openhire.communication.service.port.CommunicationPorts.PreferenceStore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
class MongoPreferenceStore implements PreferenceStore {
    private final MongoTemplate mongo;

    MongoPreferenceStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<NotificationPreferences> find(String tenantId, String subject) {
        return Optional.ofNullable(
                mongo.findById(NotificationPreferences.idFor(tenantId, subject), NotificationPreferences.class));
    }

    @Override
    public Optional<NotificationPreferences> findByEmail(String tenantId, String email) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("email").is(email)),
                NotificationPreferences.class));
    }

    @Override
    public NotificationPreferences save(NotificationPreferences preferences) {
        return mongo.save(preferences);
    }

    @Override
    public List<NotificationPreferences> withDigest() {
        return mongo.find(
                Query.query(Criteria.where("emailDigest").in("HOURLY", "DAILY_9AM")), NotificationPreferences.class);
    }
}
