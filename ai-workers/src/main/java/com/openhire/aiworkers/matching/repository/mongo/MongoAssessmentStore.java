package com.openhire.aiworkers.matching.repository.mongo;

import com.openhire.aiworkers.matching.domain.Assessment;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AssessmentStore;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Every version is a row; the row also carries the outgoing event (embedded outbox, relayed by service-support). */
@Repository
class MongoAssessmentStore implements AssessmentStore {
    static final String COLLECTION = "assessments";
    private final MongoTemplate mongo;

    MongoAssessmentStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean commandProcessed(String tenantId, String eventId) {
        return mongo.exists(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("commandEventId")
                        .is(eventId)),
                COLLECTION);
    }

    @Override
    public boolean hasComplete(
            String tenantId, String applicationId, String resumeId, int jobVersion, String rubricHash) {
        return mongo.exists(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("applicationId")
                        .is(applicationId)
                        .and("resumeId")
                        .is(resumeId)
                        .and("jobVersion")
                        .is(jobVersion)
                        .and("rubricHash")
                        .is(rubricHash)
                        .and("status")
                        .is(Assessment.COMPLETE)),
                COLLECTION);
    }

    @Override
    public void save(Assessment a, Map<String, Object> event) {
        var sameVersion = Criteria.where("tenantId")
                .is(a.tenantId())
                .and("applicationId")
                .is(a.applicationId())
                .and("jobVersion")
                .is(a.jobVersion())
                .and("current")
                .is(true);
        boolean supersedes = Assessment.COMPLETE.equals(a.status());
        boolean outranked = !supersedes
                && mongo.exists(
                        Query.query(sameVersion)
                                .addCriteria(Criteria.where("status").is(Assessment.COMPLETE)),
                        COLLECTION);
        if (supersedes)
            mongo.updateMulti(
                    Query.query(sameVersion),
                    new Update().set("current", false).set("supersededBy", a.id()),
                    COLLECTION);
        mongo.insert(
                new Document("_id", a.id())
                        .append("tenantId", a.tenantId())
                        .append("applicationId", a.applicationId())
                        .append("candidateId", a.candidateId())
                        .append("resumeId", a.resumeId())
                        .append("jobVersion", a.jobVersion())
                        .append("rubricHash", a.rubricHash())
                        .append("status", a.status())
                        .append("score", a.score())
                        .append("current", !outranked)
                        .append("commandEventId", a.commandEventId())
                        .append("createdAt", Date.from(a.createdAt()))
                        .append("payload", event.get("payload"))
                        .append("_events", List.of(event)),
                COLLECTION);
    }
}
