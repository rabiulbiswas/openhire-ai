package com.openhire.candidateservice;

import static com.openhire.support.Records.doc;
import static com.openhire.support.Records.now;

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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of the candidate profile.
 *
 * <p>Erasure keeps only the id, the tenant, the merge pointer and an {@code erased} flag; the name, emails, phones,
 * title, location, skills, experience, documents index, applications, timeline, provenance and queued events are
 * removed. Profiles that were merged into this candidate are erased with it, and merge suggestions that name the
 * candidate are deleted. {@link CandidateService#project} refuses to rebuild an erased profile.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private static final String CANDIDATES = "candidates";
    private static final List<String> KEPT = List.of("_id", "tenantId", "mergedInto", "erased", "erasedAt");

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "candidate-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var profiles = new java.util.ArrayList<Map<String, Object>>();
        for (var row : profiles(tenantId, candidateId)) {
            if (Boolean.TRUE.equals(row.get("erased"))) continue;
            var item =
                    PrivacyJson.map(row, "_events", "mergeLock", "normalizedName", "tenantId", "owner", "mergedInto");
            item.put("id", row.get("_id"));
            item.remove("_id");
            for (var field : List.of("applications", "documents", "timeline"))
                item.put(field, new java.util.ArrayList<>(doc(item.get(field)).values()));
            profiles.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("profiles", profiles);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int erased = 0;
        for (var row : profiles(tenantId, candidateId)) {
            if (Boolean.TRUE.equals(row.get("erased"))) continue;
            var update = new Update().set("erased", true).set("erasedAt", now());
            row.keySet().stream().filter(field -> !KEPT.contains(field)).forEach(update::unset);
            mongo.updateFirst(
                    Query.query(
                            Criteria.where("tenantId").is(tenantId).and("_id").is(row.get("_id"))),
                    update,
                    CANDIDATES);
            erased++;
        }
        int suggestions = removeSuggestions(tenantId, candidateId);
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("profiles", erased);
        counts.put("mergeSuggestions", suggestions);
        return Outcome.erased(counts);
    }

    private List<Document> profiles(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .orOperator(
                                Criteria.where("_id").is(candidateId),
                                Criteria.where("mergedInto").is(candidateId))),
                Document.class,
                CANDIDATES);
    }

    private int removeSuggestions(String tenantId, String candidateId) {
        var named = Query.query(Criteria.where("tenantId")
                .is(tenantId)
                .orOperator(
                        Criteria.where("a").is(candidateId), Criteria.where("b").is(candidateId)));
        var ids = mongo.find(named, Document.class, "merge_suggestions").stream()
                .map(row -> row.getString("_id"))
                .toList();
        if (ids.isEmpty()) return 0;
        mongo.remove(
                Query.query(Criteria.where("tenantId").is(tenantId).and("_id").in(ids)), "merge_dismissals");
        return (int) mongo.remove(named, "merge_suggestions").getDeletedCount();
    }
}
