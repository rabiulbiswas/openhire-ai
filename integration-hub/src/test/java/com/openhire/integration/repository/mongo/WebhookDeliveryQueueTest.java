package com.openhire.integration.repository.mongo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.integration.client.http.PinnedWebhookSender;
import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.integration.security.FileIntegrationSecrets;
import com.openhire.integration.service.IntegrationService;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class WebhookDeliveryQueueTest {
    @Test
    void fanOutIncludesSubscriptionsAfterFirstPageWithoutCrossingTenants() {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var configs = new MongoIntegrationStore(mongo, mock(IntegrationBudget.class));
                configs.indexes();
                for (int i = 0; i < 201; i++) {
                    assertTrue(configs.save(
                            new IntegrationConfig(
                                    String.format("hook%03d", i),
                                    "t",
                                    "WEBHOOK",
                                    "Events",
                                    null,
                                    "https://hooks.example.com/events",
                                    "secret://t/key",
                                    List.of("offer.accepted"),
                                    true,
                                    1),
                            0));
                }
                assertTrue(configs.save(
                        new IntegrationConfig(
                                "other",
                                "other",
                                "WEBHOOK",
                                "Other tenant",
                                null,
                                "https://hooks.example.com/events",
                                "secret://other/key",
                                List.of("offer.accepted"),
                                true,
                                1),
                        0));
                var queue = new WebhookDeliveryQueue(
                        mongo,
                        configs,
                        mock(FileIntegrationSecrets.class),
                        mock(PinnedWebhookSender.class),
                        JsonMapper.builder().build(),
                        Clock.systemUTC(),
                        false,
                        mock(IntegrationBudget.class));
                queue.enqueue("t", "event", "offer.accepted", "offer");
                queue.enqueue("t", "event", "offer.accepted", "offer");
                assertEquals(201, mongo.getCollection("webhook_deliveries").countDocuments());
                assertEquals(1, queue.history("t", "hook200").size());
                assertTrue(queue.history("other", "other").isEmpty());
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }

    @Test
    void deduplicatesSignsRetriesAndScopesReplay() throws Exception {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var configs = mock(IntegrationService.Store.class);
                var secrets = mock(FileIntegrationSecrets.class);
                var sender = mock(PinnedWebhookSender.class);
                var now = Instant.parse("2026-01-01T00:00:00Z");
                var config = new IntegrationConfig(
                        "w",
                        "t",
                        "WEBHOOK",
                        "Events",
                        null,
                        "https://hooks.example.com/events",
                        "secret://t/key",
                        List.of("offer.accepted"),
                        true,
                        1);
                when(configs.page("t", "WEBHOOK", null, 200)).thenReturn(List.of(config));
                when(configs.find("t", "WEBHOOK", "w")).thenReturn(Optional.of(config));
                when(secrets.read(any()))
                        .thenReturn("{\"signingKey\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}");
                when(sender.send(any(), any(), any())).thenReturn(503, 200);
                var queue = new WebhookDeliveryQueue(
                        mongo,
                        configs,
                        secrets,
                        sender,
                        JsonMapper.builder().build(),
                        Clock.fixed(now, ZoneOffset.UTC),
                        true,
                        mock(IntegrationBudget.class));
                queue.indexes();
                queue.enqueue("t", "event", "offer.accepted", "offer");
                queue.enqueue("t", "event", "offer.accepted", "offer");
                assertEquals(1, queue.history("t", "w").size());
                assertTrue(queue.history("other", "w").isEmpty());
                queue.dispatch();
                var first = queue.history("t", "w").getFirst();
                assertEquals("PENDING", first.getString("status"));
                assertEquals(1, first.getInteger("attempts"));
                assertFalse(first.containsKey("body"));
                var later = new WebhookDeliveryQueue(
                        mongo,
                        configs,
                        secrets,
                        sender,
                        JsonMapper.builder().build(),
                        Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC),
                        true,
                        mock(IntegrationBudget.class));
                later.dispatch();
                assertEquals("DELIVERED", later.history("t", "w").getFirst().getString("status"));
                verify(sender, times(2))
                        .send(
                                eq(config.endpoint()),
                                argThat(headers ->
                                        headers.get("X-OpenHire-Signature").startsWith("v1=")),
                                any());
                String id = first.getString("_id");
                assertFalse(queue.replay("t", "w", id));
                mongo.getCollection("webhook_deliveries")
                        .updateOne(new Document("_id", id), new Document("$set", new Document("status", "DEAD")));
                assertFalse(queue.replay("other", "w", id));
                assertTrue(queue.replay("t", "w", id));
                assertFalse(queue.replay("t", "w", id));
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
