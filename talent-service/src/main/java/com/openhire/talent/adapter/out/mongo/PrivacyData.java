package com.openhire.talent.adapter.out.mongo;

import static com.openhire.support.Records.documents;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of the candidate's talent pool data: the profile (skills, resume excerpts, evidence, stage
 * history), its vector, the invitations sent to the candidate and every stored rediscovery result that lists them.
 * Erasure deletes all of it and writes a tombstone, so the profile projector no longer rebuilds the candidate.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private static final String PROFILES = "talent_profiles";
    private static final String INVITATIONS = "talent_invitations";
    private static final String RUNS = "rediscovery_runs";

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "talent-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var result = new LinkedHashMap<String, Object>();
        var profile = mongo.findOne(
                Query.query(Criteria.where("_id").is(id(tenantId, candidateId))), Document.class, PROFILES);
        result.put(
                "profile",
                profile == null
                        ? Map.of()
                        : PrivacyJson.map(profile, "_id", "_class", "tenantId", "candidateId", "revision", "embedded"));
        var vector = mongo.findOne(
                Query.query(Criteria.where("_id").is(id(tenantId, candidateId))),
                Document.class,
                MongoVectors.COLLECTION);
        result.put("searchVectorStored", vector != null);
        result.put(
                "invitations",
                invitations(tenantId, candidateId).stream()
                        .map(row -> PrivacyJson.map(row, "_id", "_class", "tenantId", "candidateId", "actorId"))
                        .toList());
        var rediscovered = new ArrayList<Map<String, Object>>();
        for (var run : runs(tenantId, candidateId))
            for (var match : documents(run.get("matches")))
                if (candidateId.equals(match.getString("candidateId"))) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("jobTitle", run.get("jobTitle"));
                    item.put("createdAt", PrivacyJson.safe(run.get("createdAt"), Set.of()));
                    item.putAll(PrivacyJson.map(match, "candidateId"));
                    rediscovered.add(item);
                }
        result.put("rediscoveryResults", rediscovered);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int profiles = (int) mongo.remove(Query.query(Criteria.where("_id").is(id(tenantId, candidateId))), PROFILES)
                .getDeletedCount();
        int vectors = (int)
                mongo.remove(Query.query(Criteria.where("_id").is(id(tenantId, candidateId))), MongoVectors.COLLECTION)
                        .getDeletedCount();
        int invitations = (int) mongo.remove(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("candidateId")
                                .is(candidateId)),
                        INVITATIONS)
                .getDeletedCount();
        var affected = runs(tenantId, candidateId);
        if (!affected.isEmpty())
            mongo.updateMulti(
                    Query.query(Criteria.where("tenantId")
                            .is(tenantId)
                            .and("matches.candidateId")
                            .is(candidateId)),
                    new Update().pull("matches", new Document("candidateId", candidateId)),
                    RUNS);
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("profiles", profiles);
        counts.put("vectors", vectors);
        counts.put("invitations", invitations);
        counts.put("rediscoveryRuns", affected.size());
        return Outcome.erased(counts);
    }

    private static String id(String tenantId, String candidateId) {
        return tenantId + ":" + candidateId;
    }

    private List<Document> invitations(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                Document.class,
                INVITATIONS);
    }

    private List<Document> runs(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("matches.candidateId")
                        .is(candidateId)),
                Document.class,
                RUNS);
    }
}
