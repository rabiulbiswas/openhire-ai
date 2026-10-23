package com.openhire.communication.repository.mongo;

import com.openhire.communication.domain.Conversation;
import com.openhire.support.Records;
import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Export and erasure of a candidate's conversation, messages (their delivery status lives on the message), contact
 * preferences and the staff-facing texts that name the candidate.
 *
 * <p>Deleted: the conversation, every message of it (candidate-facing, with recipient details and queued dispatch
 * commands) and the candidate preferences. Staff notifications, digest items and staff emails never reference a
 * candidate id; they only mention the candidate's name in their title or text. They are found by the name recorded on
 * the candidate's conversation and the name is replaced by "a candidate" (the notification itself stays). Limits: a
 * candidate with no conversation has no recorded name to search for, and another person with the identical name gets
 * the same replacement in their notifications. The conversation is deleted last so a retry can still find the name.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private static final String REPLACEMENT = "a candidate";

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "communication-service";
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var result = new LinkedHashMap<String, Object>();
        var conversations = conversations(tenantId, candidateId);
        result.put(
                "conversations",
                conversations.stream()
                        .map(row -> PrivacyJson.map(new Document("candidateName", row.get("candidateName"))
                                .append("channel", row.get("channel"))
                                .append("lastMessage", row.get("lastMessage"))
                                .append("lastMessageAt", row.get("lastMessageAt"))))
                        .toList());
        var messages = new ArrayList<Map<String, Object>>();
        for (var row : mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("conversationId")
                        .is(Conversation.idFor(tenantId, candidateId))),
                Document.class,
                "messages")) {
            var item = new LinkedHashMap<String, Object>();
            for (var field : List.of(
                    "direction", "channel", "subject", "body", "status", "statusReason", "templateKey", "createdAt"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), Set.of()));
            if (row.get("recipient") != null) item.put("recipient", PrivacyJson.safe(row.get("recipient"), Set.of()));
            messages.add(item);
        }
        result.put("messages", messages);
        var preferences = mongo.findOne(
                Query.query(Criteria.where("_id").is(tenantId + ":" + candidateId)),
                Document.class,
                "candidate_preferences");
        result.put(
                "preferences",
                preferences == null
                        ? Map.of()
                        : PrivacyJson.map(preferences, "_id", "tenantId", "candidateId", "_class"));
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        var conversationId = Conversation.idFor(tenantId, candidateId);
        var names = new LinkedHashSet<String>();
        for (var row : conversations(tenantId, candidateId)) {
            var name = Records.text(row, "candidateName").trim();
            if (!name.isBlank() && !name.equalsIgnoreCase("Erased candidate")) names.add(name);
        }
        int notifications = 0, digests = 0, staffEmails = 0;
        for (var name : names) {
            var pattern = Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE);
            notifications += scrub(tenantId, "notifications", pattern, null);
            digests += scrub(tenantId, "digest_items", pattern, null);
            staffEmails += scrub(tenantId, "messages", pattern, "STAFF");
        }
        int messages = (int) mongo.remove(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .and("conversationId")
                                .is(conversationId)),
                        "messages")
                .getDeletedCount();
        int preferences = (int) mongo.remove(
                        Query.query(Criteria.where("_id").is(tenantId + ":" + candidateId)), "candidate_preferences")
                .getDeletedCount();
        int deletedConversations = (int) mongo.remove(
                        Query.query(Criteria.where("tenantId")
                                .is(tenantId)
                                .orOperator(
                                        Criteria.where("candidateId").is(candidateId),
                                        Criteria.where("_id").is(conversationId))),
                        "conversations")
                .getDeletedCount();
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("conversations", deletedConversations);
        counts.put("messages", messages);
        counts.put("candidatePreferences", preferences);
        counts.put("staffNotificationsAnonymised", notifications);
        counts.put("digestItemsAnonymised", digests);
        counts.put("staffEmailsAnonymised", staffEmails);
        return Outcome.erased(counts);
    }

    private List<Document> conversations(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .orOperator(
                                Criteria.where("candidateId").is(candidateId),
                                Criteria.where("_id").is(Conversation.idFor(tenantId, candidateId)))),
                Document.class,
                "conversations");
    }

    /** Replaces the name in title, subject and body (and in queued dispatch commands) of matching rows. */
    private int scrub(String tenantId, String collection, Pattern name, String audience) {
        var criteria = Criteria.where("tenantId")
                .is(tenantId)
                .orOperator(
                        Criteria.where("title").regex(name),
                        Criteria.where("subject").regex(name),
                        Criteria.where("body").regex(name));
        if (audience != null) criteria = criteria.and("audience").is(audience);
        int changed = 0;
        for (var row : mongo.find(Query.query(criteria), Document.class, collection)) {
            var update = new Update();
            for (var field : List.of("title", "subject", "body"))
                if (row.get(field) instanceof String text && name.matcher(text).find())
                    update.set(field, name.matcher(text).replaceAll(REPLACEMENT));
            if (row.get("_events") instanceof List<?> events && !events.isEmpty())
                update.set(
                        "_events",
                        events.stream().map(event -> scrubEvent(event, name)).toList());
            mongo.updateFirst(Query.query(Criteria.where("_id").is(row.get("_id"))), update, collection);
            changed++;
        }
        return changed;
    }

    private static Object scrubEvent(Object event, Pattern name) {
        var copy = new Document(Records.doc(event));
        var payload = new Document(Records.doc(copy.get("payload")));
        for (var field : List.of("subject", "body"))
            if (payload.get(field) instanceof String text)
                payload.put(field, name.matcher(text).replaceAll(REPLACEMENT));
        copy.put("payload", payload);
        return copy;
    }
}
