package com.openhire.dispatch.repository.mongo;

import com.openhire.dispatch.domain.Delivery;
import com.openhire.dispatch.service.port.DispatchPorts.DeliveryStore;
import java.time.Duration;
import java.time.Instant;
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
class MongoDeliveryStore implements DeliveryStore {
    /** A PENDING delivery untouched this long was interrupted mid-flight and is picked up again. */
    private static final Duration STALLED = Duration.ofMinutes(15);

    private final MongoTemplate mongo;

    MongoDeliveryStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Delivery> find(String messageId) {
        return Optional.ofNullable(mongo.findById(messageId, Delivery.class));
    }

    @Override
    public Delivery insertOrGet(Delivery delivery) {
        try {
            return mongo.insert(delivery);
        } catch (DuplicateKeyException redelivered) {
            return mongo.findById(delivery.id(), Delivery.class);
        }
    }

    @Override
    public Delivery save(Delivery delivery) {
        return mongo.save(delivery);
    }

    @Override
    public List<Delivery> due(Instant now, int limit) {
        var deferred = Criteria.where("status").is("DEFERRED").and("dueAt").lte(now);
        var stalled = Criteria.where("status").is("PENDING").and("updatedAt").lte(now.minus(STALLED));
        return mongo.find(
                Query.query(new Criteria().orOperator(deferred, stalled))
                        .with(Sort.by("dueAt"))
                        .limit(limit),
                Delivery.class);
    }

    @Override
    public List<Delivery> list(String tenantId, Delivery.Status status, int limit) {
        var criteria = Criteria.where("tenantId").is(tenantId);
        if (status != null) criteria = criteria.and("status").is(status);
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                        .limit(limit),
                Delivery.class);
    }

    @Override
    public long purgeFailedContent(Instant failedBefore) {
        return mongo.updateMulti(
                        Query.query(Criteria.where("status")
                                .is("FAILED")
                                .and("updatedAt")
                                .lte(failedBefore)
                                .and("body")
                                .ne(null)),
                        new Update().unset("body").unset("subject"),
                        Delivery.class)
                .getModifiedCount();
    }
}
