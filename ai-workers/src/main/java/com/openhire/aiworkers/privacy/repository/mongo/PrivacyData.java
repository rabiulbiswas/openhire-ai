package com.openhire.aiworkers.privacy.repository.mongo;

import static com.openhire.support.Records.text;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of what the AI workers derived from a candidate's resume: resume chunks (text and vectors),
 * assessments (criteria with their evidence quotes), integrity signals with their dismissals, the hashed identity
 * index entries and queued matching retries. All of it is deleted. The job-level criterion vectors are not personal
 * data and stay. After erasure the matching and integrity handlers skip the candidate. The export omits vectors and
 * the identity hashes.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "ai-workers";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var result = new LinkedHashMap<String, Object>();
        result.put(
                "resumeChunks",
                rows("resume_chunks", tenantId, candidateId).stream()
                        .map(row -> PrivacyJson.map(new Document("resumeId", row.get("resumeId"))
                                .append("section", row.get("section"))
                                .append("text", row.get("text"))))
                        .toList());
        result.put(
                "assessments",
                rows("assessments", tenantId, candidateId).stream()
                        .map(row -> PrivacyJson.map(
                                new Document("applicationId", row.get("applicationId"))
                                        .append("jobVersion", row.get("jobVersion"))
                                        .append("status", row.get("status"))
                                        .append("score", row.get("score"))
                                        .append("createdAt", row.get("createdAt"))
                                        .append("details", row.get("payload")),
                                "provenance"))
                        .toList());
        result.put(
                "integritySignals",
                rows("integrity_signals", tenantId, candidateId).stream()
                        .map(row -> PrivacyJson.map(new Document("applicationId", row.get("applicationId"))
                                .append("type", row.get("type"))
                                .append("severity", row.get("severity"))
                                .append("confidence", row.get("confidence"))
                                .append("evidence", row.get("evidence"))
                                .append("status", row.get("status"))
                                .append("dismissReason", row.get("dismissReason"))
                                .append("createdAt", row.get("createdAt"))))
                        .toList());
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        var signalIds = rows("integrity_signals", tenantId, candidateId).stream()
                .map(row -> text(row, "_id"))
                .toList();
        int dismissals = signalIds.isEmpty()
                ? 0
                : delete(
                        "integrity_dismissals",
                        Criteria.where("tenantId").is(tenantId).and("signalId").in(signalIds));
        int signals = delete("integrity_signals", byCandidate(tenantId, candidateId));
        int chunks = delete("resume_chunks", byCandidate(tenantId, candidateId));
        int assessments = delete("assessments", byCandidate(tenantId, candidateId));
        int identities = delete("identity_index", byCandidate(tenantId, candidateId));
        int retries = delete(
                "matching_retries",
                Criteria.where("tenantId")
                        .is(tenantId)
                        .and("request.payload.candidateId")
                        .is(candidateId));
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("resumeChunks", chunks);
        counts.put("assessments", assessments);
        counts.put("integritySignals", signals);
        counts.put("integrityDismissals", dismissals);
        counts.put("identityIndexEntries", identities);
        counts.put("matchingRetries", retries);
        return Outcome.erased(counts);
    }

    private static Criteria byCandidate(String tenantId, String candidateId) {
        return Criteria.where("tenantId").is(tenantId).and("candidateId").is(candidateId);
    }

    private List<Document> rows(String collection, String tenantId, String candidateId) {
        return mongo.find(Query.query(byCandidate(tenantId, candidateId)), Document.class, collection);
    }

    private int delete(String collection, Criteria criteria) {
        return (int) mongo.remove(Query.query(criteria), collection).getDeletedCount();
    }
}
