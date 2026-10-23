package com.openhire.talent.repository.mongo;

import com.openhire.talent.domain.RediscoveryRun;
import com.openhire.talent.service.port.TalentPorts.RunRepository;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoRuns implements RunRepository {
    private final MongoTemplate mongo;

    MongoRuns(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public RediscoveryRun save(RediscoveryRun run) {
        return mongo.save(run);
    }

    @Override
    public Optional<RediscoveryRun> latest(String tenantId, String jobId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("jobId").is(jobId))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt")),
                RediscoveryRun.class));
    }

    @Override
    public Optional<RediscoveryRun> latestForTenant(String tenantId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId)).with(Sort.by(Sort.Direction.DESC, "createdAt")),
                RediscoveryRun.class));
    }

    @Override
    public boolean existsByTrigger(String tenantId, String triggerEventId) {
        return mongo.exists(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("triggerEventId")
                        .is(triggerEventId)),
                RediscoveryRun.class);
    }

    @Override
    public void removeCandidate(String tenantId, String candidateId) {
        mongo.updateMulti(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("matches.candidateId")
                        .is(candidateId)),
                new Update().pull("matches", new Document("candidateId", candidateId)),
                RediscoveryRun.class);
    }
}
