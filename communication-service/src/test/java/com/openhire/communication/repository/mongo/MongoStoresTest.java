package com.openhire.communication.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.communication.domain.CommunicationTypes.Audience;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.Recipient;
import com.openhire.communication.domain.Message;
import com.openhire.communication.domain.StaffNotification;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;

/** Opt-in: creates and drops only a uniquely named oh_phase4_test_* database, with the entities' real indexes. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoStoresTest {
    private static MongoClient client;
    private static MongoTemplate mongo;
    private static MongoMessageStore messages;
    private static MongoNotificationStore notifications;
    private static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase4_test_" + UUID.randomUUID().toString().replace("-", ""));
        var resolver =
                new MongoPersistentEntityIndexResolver(mongo.getConverter().getMappingContext());
        for (var type : new Class<?>[] {Message.class, StaffNotification.class})
            resolver.resolveIndexFor(type).forEach(index -> mongo.indexOps(type).createIndex(index));
        messages = new MongoMessageStore(mongo, Clock.systemUTC());
        notifications = new MongoNotificationStore(mongo, Clock.systemUTC());
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    private static Message message(String dedupeKey) {
        return Message.outbound(
                "t1",
                "conv",
                Audience.CANDIDATE,
                Channel.EMAIL,
                new Recipient("P", "p@x.test", null),
                "S",
                "Body",
                null,
                null,
                "sub",
                dedupeKey,
                false,
                null,
                NOW);
    }

    @Test
    void manualMessagesWithoutADedupeKeyNeverCollide() {
        assertThat(messages.insert(message(null))).isTrue();
        assertThat(messages.insert(message(null))).isTrue();
        assertThat(messages.insert(message(null))).isTrue();
    }

    @Test
    void redeliveredTriggerMessagesAreRejectedByTheirDedupeKey() {
        assertThat(messages.insert(message("evt-1:interview-invitation"))).isTrue();
        assertThat(messages.insert(message("evt-1:interview-invitation"))).isFalse();
        assertThat(messages.insert(message("evt-2:interview-invitation"))).isTrue();
    }

    @Test
    void notificationsDedupeOnlyWhenTheyCarryAKey() {
        var first = StaffNotification.create(
                "t1", "user:a", "ASSIGNED", null, "T", "B", "x", null, false, "evt-9:user:a", NOW);
        var again = StaffNotification.create(
                "t1", "user:a", "ASSIGNED", null, "T", "B", "x", null, false, "evt-9:user:a", NOW);
        assertThat(notifications.insert(first)).isTrue();
        assertThat(notifications.insert(again)).isFalse();
    }
}
