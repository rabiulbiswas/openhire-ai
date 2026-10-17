package com.openhire.analytics.adapter.out.mongo;

import com.openhire.analytics.application.port.AnalyticsPorts.AssessmentFacts;
import com.openhire.analytics.domain.Facts.AssessmentFact;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** {@code fact_assessment}: one row per assessment version. */
@Repository
class MongoAssessmentFacts implements AssessmentFacts {
    static final String COLLECTION = "fact_assessment";
    private final MongoTemplate mongo;

    MongoAssessmentFacts(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void save(AssessmentFact fact) {
        mongo.save(
                new Document("_id", Docs.key(fact.tenantId(), fact.assessmentId()))
                        .append("tenantId", fact.tenantId())
                        .append("assessmentId", fact.assessmentId())
                        .append("applicationId", fact.applicationId())
                        .append("jobId", fact.jobId())
                        .append("score", fact.score())
                        .append("confidence", fact.confidence())
                        .append("model", fact.model())
                        .append("status", fact.status())
                        .append("completedAt", Docs.date(fact.completedAt()))
                        .append("screenedAt", Docs.date(fact.screenedAt())),
                COLLECTION);
    }

    @Override
    public List<AssessmentFact> forApplications(String tenantId, Collection<String> applicationIds) {
        if (applicationIds.isEmpty()) return List.of();
        var query = Query.query(
                Criteria.where("tenantId").is(tenantId).and("applicationId").in(applicationIds));
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(MongoAssessmentFacts::read)
                .toList();
    }

    @Override
    public List<AssessmentFact> completedBetween(String tenantId, Instant from, Instant to) {
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("completedAt")
                        .gt(Docs.date(from))
                        .lte(Docs.date(to)))
                .limit(Docs.MAX_ROWS);
        return mongo.find(query, Document.class, COLLECTION).stream()
                .map(MongoAssessmentFacts::read)
                .toList();
    }

    private static AssessmentFact read(Document d) {
        return new AssessmentFact(
                d.getString("tenantId"),
                d.getString("assessmentId"),
                d.getString("applicationId"),
                d.getString("jobId"),
                (int) Docs.number(d, "score"),
                d.getString("confidence"),
                d.getString("model"),
                d.getString("status"),
                Docs.instant(d, "completedAt"),
                Docs.instant(d, "screenedAt"));
    }
}
