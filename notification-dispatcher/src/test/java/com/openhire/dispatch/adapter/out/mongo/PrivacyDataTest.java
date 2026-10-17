package com.openhire.dispatch.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.support.privacy.PrivacyParticipant.Status;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivacyDataTest {
    private final InMemoryMongo mongo = new InMemoryMongo();
    private final com.openhire.support.privacy.Tombstones tombstones = InMemoryMongo.tombstones();
    private final PrivacyData data = new PrivacyData(mongo.template, tombstones);
    private final String conversation = PrivacyData.conversationId("t1", "cand-1");

    private static Document delivery(String id, String tenant, String conversation, String status) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("conversationId", conversation)
                .append("status", status)
                .append("requestedChannel", "EMAIL")
                .append("recipient", new Document("name", "Asha Rao").append("email", "asha@example.test"))
                .append("subject", status.equals("SENT") ? null : "Interview")
                .append("body", status.equals("SENT") ? null : "Hello Asha");
    }

    @BeforeEach
    void rows() {
        mongo.put("deliveries", delivery("d1", "t1", conversation, "PENDING"));
        mongo.put("deliveries", delivery("d2", "t1", conversation, "FAILED"));
        mongo.put("deliveries", delivery("d3", "t1", conversation, "SENT"));
        mongo.put("deliveries", delivery("d4", "t1", PrivacyData.conversationId("t1", "cand-2"), "PENDING"));
        mongo.put("deliveries", delivery("d5", "t2", PrivacyData.conversationId("t2", "cand-1"), "PENDING"));
        mongo.put("deliveries", delivery("d6", "t1", null, "PENDING"));
    }

    @Test
    void exportListsTheCandidatesDeliveries() {
        var deliveries = (List<?>) data.export("t1", "cand-1").get("deliveries");

        assertThat(deliveries).hasSize(3);
        assertThat(((Map<?, ?>) deliveries.getFirst()).keySet().stream().map(Object::toString))
                .doesNotContain("_events", "_id", "conversationId", "tenantId");
    }

    @Test
    void erasureDeletesHoldingDeliveriesAndAnonymisesSentOnes() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts()).containsEntry("deliveriesDeleted", 2).containsEntry("sentDeliveriesAnonymised", 1);
        assertThat(mongo.one("deliveries", "d1")).isNull();
        assertThat(mongo.one("deliveries", "d2")).isNull();
        var sent = mongo.one("deliveries", "d3");
        assertThat(sent).doesNotContainKeys("recipient", "subject", "body");
        assertThat(sent.getString("status")).isEqualTo("SENT");
        assertThat(tombstones.isErased("t1", conversation)).isTrue();
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesTenantsAndUnlinkedDeliveriesAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        for (var id : List.of("d4", "d5", "d6"))
            assertThat(mongo.one("deliveries", id)).isNotNull();
        assertThat(mongo.one("deliveries", "d4").get("recipient")).isNotNull();
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(data.erase("t1", "req-1", "cand-1").status()).isEqualTo(Status.NOTHING_HELD);
    }
}
