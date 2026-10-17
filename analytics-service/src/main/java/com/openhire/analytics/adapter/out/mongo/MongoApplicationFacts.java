package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.ApplicationFacts;
import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.ApplicationFact.StageEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** {@code fact_application}, written only by the application-events consumer. */
@Repository
class MongoApplicationFacts implements ApplicationFacts {
    static final String COLLECTION = "fact_application";
    private final MongoTemplate mongo;

    MongoApplicationFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<ApplicationFact> find(String tenantId, String applicationId) {
        return Optional.ofNullable(mongo.findById(Docs.key(tenantId, applicationId), Document.class, COLLECTION))
                .map(MongoApplicationFacts::read);
    }

    @Override
    public void save(ApplicationFact fact) {
        var timeline = fact.timeline().stream()
                .map(entry -> new Document("stage", entry.stage()).append("at", Docs.date(entry.at())))
                .toList();
        var document = new Document("_id", Docs.key(fact.tenantId(), fact.applicationId()))
                .append("tenantId", fact.tenantId())
                .append("applicationId", fact.applicationId())
                .append("jobId", fact.jobId())
                .append("jobTitle", fact.jobTitle())
                .append("source", fact.source())
                .append("submittedAt", Docs.date(fact.submittedAt()))
                .append("screenedAt", Docs.date(fact.screenedAt()))
                .append("timeline", timeline)
                .append("hiredAt", Docs.date(fact.hiredAt()))
                .append("rejectedAt", Docs.date(fact.rejectedAt()))
                .append("withdrawnAt", Docs.date(fact.withdrawnAt()))
                .append("lastEventAt", Docs.date(fact.lastEventAt()));
        mongo.save(document, COLLECTION);
    }

    @Override
    public List<ApplicationFact> touchedSince(String tenantId, String jobId, Instant since) {
        var criteria =
                Criteria.where("tenantId").is(tenantId).and("lastEventAt").gte(Docs.date(since));
        if (jobId != null) criteria = criteria.and("jobId").is(jobId);
        return mongo.find(Query.query(criteria).limit(Docs.MAX_ROWS), Document.class, COLLECTION).stream()
                .map(MongoApplicationFacts::read)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ApplicationFact read(Document d) {
        var timeline = ((List<Document>) d.getOrDefault("timeline", List.of()))
                .stream()
                        .map(entry -> new StageEntry(entry.getString("stage"), Docs.instant(entry, "at")))
                        .toList();
        return new ApplicationFact(
                d.getString("tenantId"),
                d.getString("applicationId"),
                d.getString("jobId"),
                d.getString("jobTitle"),
                d.getString("source"),
                Docs.instant(d, "submittedAt"),
                Docs.instant(d, "screenedAt"),
                timeline,
                Docs.instant(d, "hiredAt"),
                Docs.instant(d, "rejectedAt"),
                Docs.instant(d, "withdrawnAt"),
                Docs.instant(d, "lastEventAt"));
    }
}
