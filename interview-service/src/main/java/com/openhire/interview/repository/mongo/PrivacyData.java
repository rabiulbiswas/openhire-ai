package com.openhire.interview.repository.mongo;

import static com.openhire.support.Records.doc;
import static com.openhire.support.Records.documents;

import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of the candidate's interview loops. Scorecards (ratings and evidence are data about the
 * candidate, and the debrief is built from them) are deleted; so are the candidate's name, the meeting links and
 * cancellation texts, and the same values inside queued events. Round ids, times, statuses and the panel (staff) stay,
 * so interviewer calendars and the history of what was scheduled remain intact. Scheduling holds of every round are
 * released. A loop is never created again for a tombstoned candidate (see InterviewApplicationService#createLoop).
 */
@Component
class PrivacyData implements PrivacyParticipant {
    static final String ERASED_NAME = "Erased candidate";
    private static final String LOOPS = "interview_loops";
    private static final Set<String> EVENT_FIELDS = Set.of("meetingUrl", "bookingUrl", "reason", "candidateName");

    private final MongoTemplate mongo;
    private final SlotHolds holds;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, SlotHolds holds, Tombstones tombstones) {
        this.mongo = mongo;
        this.holds = holds;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "interview-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var interviews = new ArrayList<Map<String, Object>>();
        for (var loop : loops(tenantId, candidateId)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("applicationId", loop.get("applicationId"));
            item.put("jobTitle", loop.get("jobTitle"));
            item.put("candidateName", loop.get("candidateName"));
            var rounds = new ArrayList<Map<String, Object>>();
            for (var round : documents(loop.get("rounds"))) {
                var entry = new LinkedHashMap<String, Object>();
                for (var field : List.of("name", "status", "slot", "cancelReason", "feedbackComplete"))
                    if (round.get(field) != null) entry.put(field, PrivacyJson.safe(round.get(field), Set.of()));
                entry.put(
                        "scorecards",
                        documents(round.get("scorecards")).stream()
                                .map(card -> PrivacyJson.map(new Document("ratings", card.get("ratings"))
                                        .append("evidence", card.get("evidence"))
                                        .append("recommendation", card.get("recommendation"))
                                        .append("submittedAt", card.get("submittedAt"))))
                                .toList());
                rounds.add(entry);
            }
            item.put("rounds", rounds);
            interviews.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("interviews", interviews);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        int loopsErased = 0, scorecards = 0, holdsReleased = 0;
        for (var loop : loops(tenantId, candidateId)) {
            var rounds = new ArrayList<Document>();
            boolean changed = !ERASED_NAME.equals(loop.getString("candidateName"));
            int cards = 0;
            for (var source : documents(loop.get("rounds"))) {
                var round = new Document(source);
                cards += documents(round.get("scorecards")).size();
                changed |= !documents(round.get("scorecards")).isEmpty()
                        || round.get("meetingUrl") != null
                        || round.get("cancelReason") != null;
                round.put("scorecards", List.of());
                round.remove("meetingUrl");
                round.remove("cancelReason");
                rounds.add(round);
                holds.clear(tenantId, String.valueOf(round.get("roundId")), panelKeys(round));
                holdsReleased++;
            }
            var events = new ArrayList<Document>();
            for (var event : documents(loop.get("_events"))) {
                var copy = new Document(event);
                var payload = new Document(doc(copy.get("payload")));
                changed |= EVENT_FIELDS.stream().anyMatch(payload::containsKey);
                EVENT_FIELDS.forEach(payload::remove);
                copy.put("payload", payload);
                events.add(copy);
            }
            if (!changed) continue;
            mongo.updateFirst(
                    Query.query(
                            Criteria.where("tenantId").is(tenantId).and("_id").is(loop.get("_id"))),
                    new Update()
                            .set("candidateName", ERASED_NAME)
                            .set("rounds", rounds)
                            .set("_events", events)
                            .inc("revision", 1),
                    LOOPS);
            loopsErased++;
            scorecards += cards;
        }
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("interviewLoops", loopsErased);
        counts.put("scorecards", scorecards);
        // Released holds are reported only when the loop itself held something, so a repeat request reports zero.
        counts.put("schedulingHolds", loopsErased == 0 ? 0 : holdsReleased);
        return Outcome.erased(counts);
    }

    private List<Document> loops(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("candidateId")
                        .is(candidateId)),
                Document.class,
                LOOPS);
    }

    private static List<String> panelKeys(Document round) {
        var keys = new ArrayList<String>();
        for (var member : documents(round.get("panel"))) {
            var email = member.getString("email");
            keys.add(
                    email != null && !email.isBlank()
                            ? "email:" + email.toLowerCase(Locale.ROOT)
                            : "name:" + String.valueOf(member.get("name")).toLowerCase(Locale.ROOT));
        }
        return keys;
    }
}
