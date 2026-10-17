package com.openhire.employee.adapter.out.mongo;

import com.openhire.employee.application.port.EmployeePorts.SagaRepository;
import com.openhire.employee.domain.HireSaga;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@Repository
class MongoSagaRepository implements SagaRepository {
    private final MongoTemplate mongo;

    MongoSagaRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public HireSaga insertIfAbsent(HireSaga saga) {
        try {
            return mongo.insert(saga);
        } catch (DuplicateKeyException exists) {
            return findById(saga.tenantId(), saga.id()).orElseThrow(() -> exists);
        }
    }

    @Override
    public HireSaga save(HireSaga saga) {
        return mongo.save(saga);
    }

    @Override
    public Optional<HireSaga> findById(String tenantId, String id) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(id)), HireSaga.class));
    }

    @Override
    public Optional<HireSaga> findByApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)),
                HireSaga.class));
    }

    @Override
    public List<HireSaga> list(String tenantId, int limit) {
        return mongo.find(
                Query.query(Criteria.where("tenantId").is(tenantId))
                        .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                        .limit(limit),
                HireSaga.class);
    }

    /** One findAndModify: only the instance that sets the lease gets the saga, so two workers never share one. */
    @Override
    public Optional<HireSaga> claimNext(Instant now, Duration lease, String owner) {
        var due = Criteria.where("nextAttemptAt")
                .lte(now)
                .andOperator(new Criteria()
                        .orOperator(
                                Criteria.where("leaseUntil").isNull(),
                                Criteria.where("leaseUntil").lte(now)));
        return Optional.ofNullable(mongo.findAndModify(
                Query.query(due).with(Sort.by("nextAttemptAt")),
                new Update()
                        .set("leaseUntil", now.plus(lease))
                        .set("leaseOwner", owner)
                        .inc("revision", 1),
                FindAndModifyOptions.options().returnNew(true),
                HireSaga.class));
    }
}
