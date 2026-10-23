package com.openhire.dispatch.repository.mongo;

import com.openhire.support.privacy.PrivacyJson;
import com.openhire.support.privacy.PrivacyParticipant;
import com.openhire.support.privacy.Tombstones;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * Export and erasure of the deliveries addressed to a candidate. A delivery is linked to the candidate through the
 * candidate conversation of the message that requested it (the id is derived from tenant and candidate, as
 * communication-service derives it); deliveries created before that link existed cannot be found here.
 *
 * <p>Deliveries that still hold content or recipient details (pending, deferred, failed) are deleted. A sent delivery
 * has its subject and text removed when it is sent, but it keeps the recipient name, email and phone; those are
 * removed on erasure and only the status record stays. The conversation id is tombstoned, so a late dispatch
 * command for the candidate is dropped.
 */
@Component
class PrivacyData implements PrivacyParticipant {
    private static final String DELIVERIES = "deliveries";

    private final MongoTemplate mongo;
    private final Tombstones tombstones;

    PrivacyData(MongoTemplate mongo, Tombstones tombstones) {
        this.mongo = mongo;
        this.tombstones = tombstones;
    }

    @Override
    public String service() {
        return "notification-dispatcher";
    }

    static String conversationId(String tenantId, String candidateId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000" + candidateId).getBytes(StandardCharsets.UTF_8));
            return "conv_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Map<String, Object> export(String tenantId, String candidateId) {
        var deliveries = new ArrayList<Map<String, Object>>();
        for (var row : deliveries(tenantId, candidateId)) {
            var item = new LinkedHashMap<String, Object>();
            for (var field : List.of(
                    "requestedChannel",
                    "channelUsed",
                    "category",
                    "status",
                    "reason",
                    "subject",
                    "body",
                    "createdAt",
                    "updatedAt"))
                if (row.get(field) != null) item.put(field, PrivacyJson.safe(row.get(field), java.util.Set.of()));
            if (row.get("recipient") != null)
                item.put("recipient", PrivacyJson.safe(row.get("recipient"), java.util.Set.of()));
            deliveries.add(item);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("deliveries", deliveries);
        return result;
    }

    @Override
    public Outcome erase(String tenantId, String requestId, String candidateId) {
        var conversation = conversationId(tenantId, candidateId);
        var holding = Query.query(Criteria.where("tenantId")
                .is(tenantId)
                .and("conversationId")
                .is(conversation)
                .and("status")
                .in("PENDING", "DEFERRED", "FAILED"));
        int deleted = (int) mongo.remove(holding, DELIVERIES).getDeletedCount();
        var sent = Query.query(Criteria.where("tenantId")
                .is(tenantId)
                .and("conversationId")
                .is(conversation)
                .and("status")
                .is("SENT")
                .and("recipient")
                .exists(true));
        int anonymised = mongo.find(sent, Document.class, DELIVERIES).size();
        if (anonymised > 0)
            mongo.updateMulti(
                    sent, new Update().unset("recipient").unset("subject").unset("body"), DELIVERIES);
        tombstones.mark(tenantId, conversation, requestId);
        tombstones.mark(tenantId, candidateId, requestId);
        var counts = new LinkedHashMap<String, Integer>();
        counts.put("deliveriesDeleted", deleted);
        counts.put("sentDeliveriesAnonymised", anonymised);
        return Outcome.erased(counts);
    }

    private List<Document> deliveries(String tenantId, String candidateId) {
        return mongo.find(
                Query.query(Criteria.where("tenantId")
                        .is(tenantId)
                        .and("conversationId")
                        .is(conversationId(tenantId, candidateId))),
                Document.class,
                DELIVERIES);
    }
}
