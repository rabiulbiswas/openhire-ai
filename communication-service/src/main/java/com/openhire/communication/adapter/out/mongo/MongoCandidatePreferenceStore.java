package com.openhire.communication.adapter.out.mongo;

import com.openhire.communication.application.port.CommunicationPorts.CandidatePreferenceStore;
import com.openhire.communication.domain.CandidatePreferences;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class MongoCandidatePreferenceStore implements CandidatePreferenceStore {
    private final MongoTemplate mongo;

    MongoCandidatePreferenceStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<CandidatePreferences> find(String tenantId, String candidateId) {
        return Optional.ofNullable(
                mongo.findById(CandidatePreferences.idFor(tenantId, candidateId), CandidatePreferences.class));
    }

    @Override
    public CandidatePreferences save(CandidatePreferences preferences) {
        return mongo.save(preferences);
    }
}
