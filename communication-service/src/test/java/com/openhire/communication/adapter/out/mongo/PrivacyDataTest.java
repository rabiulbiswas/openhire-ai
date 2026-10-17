package com.openhire.communication.adapter.out.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.communication.domain.Conversation;
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
    private final String conversation = Conversation.idFor("t1", "cand-1");
    private final String otherConversation = Conversation.idFor("t1", "cand-2");

    @BeforeEach
    void rows() {
        mongo.put(
                "conversations",
                new Document("_id", conversation)
                        .append("tenantId", "t1")
                        .append("candidateId", "cand-1")
                        .append("candidateName", "Asha Rao")
                        .append("channel", "EMAIL")
                        .append("lastMessage", "See you on Monday"));
        mongo.put(
                "conversations",
                new Document("_id", otherConversation)
                        .append("tenantId", "t1")
                        .append("candidateId", "cand-2")
                        .append("candidateName", "Ben Other"));
        mongo.put("messages", message("m1", conversation, "Hello Asha", "asha@example.test"));
        mongo.put("messages", message("m2", conversation, "Your interview", "asha@example.test"));
        mongo.put("messages", message("m3", otherConversation, "Hello Ben", "ben@example.test"));
        mongo.put(
                "messages",
                new Document("_id", "m4")
                        .append("tenantId", "t1")
                        .append("audience", "STAFF")
                        .append("subject", "You're interviewing Asha Rao")
                        .append("body", "Technical round with asha rao at 10:00")
                        .append(
                                "_events",
                                List.of(new Document(
                                        "payload", new Document("subject", "You're interviewing Asha Rao")))));
        mongo.put(
                "candidate_preferences",
                new Document("_id", "t1:cand-1").append("tenantId", "t1").append("preferredChannel", "EMAIL"));
        mongo.put("candidate_preferences", new Document("_id", "t1:cand-2").append("tenantId", "t1"));
        mongo.put("notifications", notification("n1", "t1", "Asha Rao cancelled Technical", "Cancelled"));
        mongo.put("notifications", notification("n2", "t1", "Ben Other cancelled Technical", "Cancelled"));
        mongo.put("notifications", notification("n3", "t2", "Asha Rao cancelled Technical", "Cancelled"));
        mongo.put(
                "digest_items",
                new Document("_id", "d1")
                        .append("tenantId", "t1")
                        .append("title", "New hire")
                        .append("body", "Asha Rao joins"));
    }

    private static Document message(String id, String conversation, String body, String email) {
        return new Document("_id", id)
                .append("tenantId", "t1")
                .append("conversationId", conversation)
                .append("audience", "CANDIDATE")
                .append("direction", "OUTBOUND")
                .append("body", body)
                .append("status", "DELIVERED")
                .append("recipient", new Document("name", "x").append("email", email))
                .append("_events", List.of(new Document("payload", new Document("body", body))));
    }

    private static Document notification(String id, String tenant, String title, String body) {
        return new Document("_id", id)
                .append("tenantId", tenant)
                .append("recipient", "user:u1")
                .append("title", title)
                .append("body", body);
    }

    @Test
    void exportHasTheThreadAndPreferencesOfThisCandidateOnly() {
        var section = data.export("t1", "cand-1");

        assertThat((List<?>) section.get("conversations")).hasSize(1);
        var messages = (List<?>) section.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(((Map<?, ?>) messages.getFirst()).get("body")).isEqualTo("Hello Asha");
        assertThat(((Map<?, ?>) messages.getFirst()).keySet().stream().map(Object::toString))
                .doesNotContain("_events", "_id", "dedupeKey", "actorId");
        assertThat(((Map<?, ?>) section.get("preferences")).get("preferredChannel"))
                .isEqualTo("EMAIL");
        assertThat(section.toString()).doesNotContain("Ben").doesNotContain("ben@");
    }

    @Test
    void erasureDeletesTheThreadAndAnonymisesStaffTextsThatNameTheCandidate() {
        var outcome = data.erase("t1", "req-1", "cand-1");

        assertThat(outcome.status()).isEqualTo(Status.ERASED);
        assertThat(outcome.counts())
                .containsEntry("conversations", 1)
                .containsEntry("messages", 2)
                .containsEntry("candidatePreferences", 1)
                .containsEntry("staffNotificationsAnonymised", 1)
                .containsEntry("digestItemsAnonymised", 1)
                .containsEntry("staffEmailsAnonymised", 1);
        assertThat(mongo.one("messages", "m1")).isNull();
        assertThat(mongo.one("conversations", conversation)).isNull();
        assertThat(mongo.one("candidate_preferences", "t1:cand-1")).isNull();
        assertThat(mongo.one("notifications", "n1").getString("title")).isEqualTo("a candidate cancelled Technical");
        assertThat(mongo.one("digest_items", "d1").getString("body")).isEqualTo("a candidate joins");
        var staffEmail = mongo.one("messages", "m4");
        assertThat(staffEmail.getString("subject")).isEqualTo("You're interviewing a candidate");
        assertThat(staffEmail.getString("body")).isEqualTo("Technical round with a candidate at 10:00");
        var event = (Document)
                staffEmail.getList("_events", Document.class).getFirst().get("payload");
        assertThat(event.getString("subject")).doesNotContain("Asha");
        assertThat(tombstones.isErased("t1", "cand-1")).isTrue();
    }

    @Test
    void otherCandidatesAndTenantsAreUntouched() {
        data.erase("t1", "req-1", "cand-1");

        assertThat(mongo.one("messages", "m3")).isNotNull();
        assertThat(mongo.one("conversations", otherConversation)).isNotNull();
        assertThat(mongo.one("candidate_preferences", "t1:cand-2")).isNotNull();
        assertThat(mongo.one("notifications", "n2").getString("title")).isEqualTo("Ben Other cancelled Technical");
        assertThat(mongo.one("notifications", "n3").getString("title")).isEqualTo("Asha Rao cancelled Technical");
    }

    @Test
    void erasingTwiceHoldsNothingTheSecondTime() {
        data.erase("t1", "req-1", "cand-1");

        var again = data.erase("t1", "req-1", "cand-1");

        assertThat(again.status()).isEqualTo(Status.NOTHING_HELD);
        assertThat(again.counts().values()).containsOnly(0);
    }
}
