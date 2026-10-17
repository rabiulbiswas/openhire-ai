package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.HealthStore;
import com.openhire.analytics.domain.ServiceSample;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** {@code platform_health}: the rolling series of polls. A TTL index on {@code at} drops what is older than a day. */
@Repository
class MongoHealthStore implements HealthStore {
    static final String COLLECTION = "platform_health";
    private final MongoTemplate mongo;

    MongoHealthStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void add(ServiceSample s) {
        mongo.insert(
                new Document("service", s.service())
                        .append("at", Docs.date(s.at()))
                        .append("up", s.up())
                        .append("healthStatus", s.healthStatus())
                        .append("responseMs", s.responseMs())
                        .append("requestCount", s.requestCount())
                        .append("totalSeconds", s.totalSeconds())
                        .append("errorCount", s.errorCount())
                        .append("requests", s.requests())
                        .append("errors", s.errors())
                        .append("timeSeconds", s.timeSeconds())
                        .append("maxSeconds", s.maxSeconds()),
                COLLECTION);
    }

    @Override
    public Optional<ServiceSample> latest(String service) {
        var query = Query.query(Criteria.where("service").is(service))
                .with(Sort.by(Sort.Direction.DESC, "at"))
                .limit(1);
        return Optional.ofNullable(mongo.findOne(query, Document.class, COLLECTION))
                .map(MongoHealthStore::read);
    }

    @Override
    public List<ServiceSample> since(String service, Instant since) {
        var query = Query.query(Criteria.where("service").is(service).and("at").gte(Docs.date(since)))
                .with(Sort.by(Sort.Direction.ASC, "at"));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(MongoHealthStore::read)
                .toList();
    }

    private static ServiceSample read(Document d) {
        return new ServiceSample(
                d.getString("service"),
                Docs.instant(d, "at"),
                Boolean.TRUE.equals(d.get("up")),
                d.getString("healthStatus"),
                Docs.number(d, "responseMs"),
                d.get("requestCount") == null ? -1 : Docs.number(d, "requestCount"),
                d.get("totalSeconds") instanceof Number n ? n.doubleValue() : 0,
                d.get("errorCount") == null ? -1 : Docs.number(d, "errorCount"),
                Docs.number(d, "requests"),
                Docs.number(d, "errors"),
                d.get("timeSeconds") instanceof Number n ? n.doubleValue() : 0,
                d.get("maxSeconds") instanceof Number n ? n.doubleValue() : 0);
    }
}
