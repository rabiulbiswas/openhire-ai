package com.openhire.offer.repository.mongo;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.service.port.OfferPorts.OfferRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoOfferRepository implements OfferRepository {
    private final MongoTemplate mongo;

    MongoOfferRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Offer save(Offer offer) {
        return mongo.save(offer);
    }

    @Override
    public Optional<Offer> find(String tenantId, String offerId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").is(offerId)), Offer.class));
    }

    @Override
    public Optional<Offer> findByApplication(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findOne(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)),
                Offer.class));
    }

    @Override
    public List<Offer> list(String tenantId, Status status, int limit) {
        var criteria = Criteria.where("tenantId").is(tenantId);
        if (status != null) criteria = criteria.and("status").is(status);
        return mongo.find(
                Query.query(criteria)
                        .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                        .limit(limit),
                Offer.class);
    }

    @Override
    public List<Offer> findExpired(Instant now, int limit) {
        return mongo.find(
                Query.query(Criteria.where("status")
                                .in(Status.SENT, Status.VIEWED, Status.NEGOTIATING)
                                .and("expiresAt")
                                .lte(now))
                        .limit(limit),
                Offer.class);
    }
}
