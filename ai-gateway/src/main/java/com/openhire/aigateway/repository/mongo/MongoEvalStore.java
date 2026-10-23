package com.openhire.aigateway.repository.mongo;

import com.openhire.aigateway.domain.AiTypes.RunStatus;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.service.port.AiPorts.EvalStore;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoEvalStore implements EvalStore {
    private final MongoTemplate mongo;

    MongoEvalStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // ---- evaluation runs ----

    @Override
    public EvalRun save(EvalRun run) {
        return mongo.save(run);
    }

    @Override
    public Optional<EvalRun> find(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(tenantId + ":" + id)),
                EvalRun.class));
    }

    @Override
    public Optional<EvalRun> running(String tenantId, Instant notBefore) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("status")
                                .is(RunStatus.RUNNING)
                                .and("startedAt")
                                .gte(notBefore))
                        .with(Sort.by(Sort.Direction.DESC, "startedAt")),
                EvalRun.class));
    }
}
