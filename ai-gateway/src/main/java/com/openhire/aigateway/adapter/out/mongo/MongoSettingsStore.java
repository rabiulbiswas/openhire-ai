package com.openhire.aigateway.adapter.out.mongo;

import com.openhire.aigateway.application.port.AiPorts.SettingsStore;
import com.openhire.aigateway.domain.AiSettings;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MongoSettingsStore implements SettingsStore {
    private final MongoTemplate mongo;

    MongoSettingsStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---- settings ----

    @Override
    public Optional<AiSettings> find(String tenantId) {
        return Optional.ofNullable(mongo.findById(tenantId, AiSettings.class));
    }

    @Override
    public AiSettings save(AiSettings settings) {
        return mongo.save(settings);
    }
}
