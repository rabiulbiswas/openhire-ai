package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.OfferFacts;
import com.openhire.analytics.domain.Facts.OfferFact;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** {@code fact_offer}, written only by the offer-events consumer. */
@Repository
class MongoOfferFacts implements OfferFacts {
    static final String COLLECTION = "fact_offer";
    private final MongoTemplate mongo;

    MongoOfferFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<OfferFact> find(String tenantId, String offerId) {
        return Optional.ofNullable(mongo.findById(Docs.key(tenantId, offerId), Document.class, COLLECTION))
                .map(MongoOfferFacts::read);
    }

    @Override
    public void save(OfferFact f) {
        mongo.save(
                new Document("_id", Docs.key(f.tenantId(), f.offerId()))
                        .append("tenantId", f.tenantId())
                        .append("offerId", f.offerId())
                        .append("applicationId", f.applicationId())
                        .append("jobId", f.jobId())
                        .append("createdAt", Docs.date(f.createdAt()))
                        .append("sentAt", Docs.date(f.sentAt()))
                        .append("acceptedAt", Docs.date(f.acceptedAt()))
                        .append("declinedAt", Docs.date(f.declinedAt()))
                        .append("expiredAt", Docs.date(f.expiredAt()))
                        .append("rescindedAt", Docs.date(f.rescindedAt()))
                        .append("lastEventAt", Docs.date(f.lastEventAt())),
                COLLECTION);
    }

    @Override
    public List<OfferFact> touchedSince(String tenantId, Instant since) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("lastEventAt")
                        .gte(Docs.date(since)))
                .limit(Docs.MAX_ROWS);
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(MongoOfferFacts::read)
                .toList();
    }

    private static OfferFact read(Document d) {
        return new OfferFact(
                d.getString("tenantId"),
                d.getString("offerId"),
                d.getString("applicationId"),
                d.getString("jobId"),
                Docs.instant(d, "createdAt"),
                Docs.instant(d, "sentAt"),
                Docs.instant(d, "acceptedAt"),
                Docs.instant(d, "declinedAt"),
                Docs.instant(d, "expiredAt"),
                Docs.instant(d, "rescindedAt"),
                Docs.instant(d, "lastEventAt"));
    }
}
