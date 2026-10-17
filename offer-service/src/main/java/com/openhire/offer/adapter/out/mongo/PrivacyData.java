package com.openhire.offer.adapter.out.mongo;

import static com.openhire.support.Records.doc;
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
 * Export and erasure of the candidate's offers.
 *
 * <p>An accepted offer is part of the employment record and is retained untouched (the answer is
 * RETAINED_LEGAL_BASIS). Every other offer is anonymised: the candidate name becomes "Erased candidate", the letter
 * texts, cover notes, the candidate's negotiation message, decline reason and acceptance name are deleted, as are the
 * same values inside queued events. Terms, versions, approval steps and statuses stay, so approval and compensation
 * history is intact. No offer is created again for a tombstoned candidate.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    static final String ERASED_NAME = "Erased candidate";
    static final String RETAINED_NOTE = "Accepted offers are kept as part of the employment record";
    private static final String OFFERS = "offers";
    private static final Set<String> EVENT_FIELDS =
            Set.of("message", "acceptedBy", "reason", "candidateName", "candidateNote");
    private static final List<String> FREE_TEXT = List.of("candidateNote", "responseReason", "acceptedBy");

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "offer-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var offers = new ArrayList<Map<String, Object>>();
        for (var row : offers(tenantId, candidateId)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("applicationId", row.get("applicationId"));
            for (var field : List.of(
                    "jobTitle",
                    "status",
                    "candidateName",
                    "candidateNote",
                    "responseReason",
                    "acceptedBy",
                    "expiresAt",
                    "sentAt",
                    "viewedAt",
                    "respondedAt",
                    "createdAt"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), Set.of()));
            item.put(
                    "versions",
                    documents(row.get("versions")).stream()
                            .map(version -> PrivacyJson.map(version, "sha256", "createdBy"))
                            .toList());
            offers.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("offers", offers);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int retained = 0, anonymised = 0, letters = 0;
        for (var row : offers(tenantId, candidateId)) {
            if ("ACCEPTED".equals(row.getString("status"))) {
                retained++;
                continue;
            }
            var versions = new ArrayList<Document>();
            boolean changed = !ERASED_NAME.equals(row.getString("candidateName"));
            int removedLetters = 0;
            for (var source : documents(row.get("versions"))) {
                var version = new Document(source);
                if (version.get("letterText") != null) removedLetters++;
                changed |= version.get("letterText") != null || version.get("coverNote") != null;
                version.remove("letterText");
                version.remove("coverNote");
                versions.add(version);
            }
            var events = new ArrayList<Document>();
            for (var event : documents(row.get("_events"))) {
                var copy = new Document(event);
                var payload = new Document(doc(copy.get("payload")));
                changed |= EVENT_FIELDS.stream().anyMatch(payload::containsKey);
                EVENT_FIELDS.forEach(payload::remove);
                copy.put("payload", payload);
                events.add(copy);
            }
            changed |= FREE_TEXT.stream().anyMatch(row::containsKey);
            if (!changed) continue;
            var update = new Update()
                    .set("candidateName", ERASED_NAME)
                    .set("versions", versions)
                    .set("_events", events)
                    .inc("revision", 1);
            FREE_TEXT.forEach(update::unset);
            mongo.updateFirst(
                    Query.query(
                            Criteria.where("tenantId").is(tenantId).and("_id").is(row.get("_id"))),
                    update,
                    OFFERS);
            anonymised++;
            letters += removedLetters;
        }
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("offersAnonymised", anonymised);
        counts.put("letters", letters);
        if (retained > 0) {
            counts.put("acceptedOffersRetained", retained);
            return Outcome.retained(RETAINED_NOTE, counts);
        }
        return Outcome.erased(counts);
    }

    private List<Document> offers(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                Document.class,
                OFFERS);
    }
}
