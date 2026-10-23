package com.openhire.talent.repository.mongo;

import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.Consent;
import com.openhire.talent.domain.TalentTypes.ProfileState;
import com.openhire.talent.service.port.TalentPorts.ConcurrentUpdate;
import com.openhire.talent.service.port.TalentPorts.ProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoProfiles implements ProfileRepository {
    private final MongoTemplate mongo;

    MongoProfiles(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<TalentProfile> find(String tenantId, String candidateId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                TalentProfile.class));
    }

    @Override
    public Optional<TalentProfile> findByApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applications.applicationId")
                        .is(applicationId)),
                TalentProfile.class));
    }

    @Override
    public TalentProfile save(TalentProfile profile) {
        try {
            return mongo.save(profile);
        } catch (OptimisticLockingFailureException | DuplicateKeyException conflict) {
            throw new ConcurrentUpdate(conflict);
        }
    }

    @Override
    public void delete(String tenantId, String candidateId) {
        mongo.remove(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                TalentProfile.class);
    }

    @Override
    public List<TalentProfile> searchable(String tenantId, Instant now, boolean includeEmployees) {
        var criteria = Criteria.where("tenantId")
                .is(tenantId)
                .and("consent")
                .is(Consent.GRANTED)
                .and("state")
                .is(ProfileState.ACTIVE)
                .and("contentBuilt")
                .is(true)
                .and("retentionUntil")
                .gt(now);
        if (!includeEmployees) criteria = criteria.and("employee").ne(true);
        return mongo.find(Query.query(criteria), TalentProfile.class);
    }

    @Override
    public List<TalentProfile> due(Instant now, int limit) {
        return mongo.find(
                Query.query(Criteria.where("state")
                                .is(ProfileState.ACTIVE)
                                .and("retentionUntil")
                                .lte(now))
                        .limit(limit),
                TalentProfile.class);
    }

    @Override
    public List<TalentProfile> awaitingEmbedding(int limit) {
        return mongo.find(
                Query.query(Criteria.where("consent")
                                .is(Consent.GRANTED)
                                .and("state")
                                .is(ProfileState.ACTIVE)
                                .and("contentBuilt")
                                .is(true)
                                .and("embedded")
                                .is(false))
                        .limit(limit),
                TalentProfile.class);
    }
}
