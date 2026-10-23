package com.openhire.aigateway.repository.mongo;

import static org.assertj.core.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoLogRetentionTest {
    @Test
    void purgeIsBoundedTenantScopedAndPreservesRecentAndUnpublishedRecords() {
        var database = "oh_phase7_log_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"))) {
            try {
                var mongo = new MongoTemplate(client, database);
                var logs = mongo.getCollection("ai_requests");
                var old = Date.from(Instant.parse("2020-01-01T00:00:00Z"));
                logs.insertMany(List.of(
                        new Document("_id", "old1")
                                .append("tenantId", "a")
                                .append("createdAt", old)
                                .append("_events", List.of()),
                        new Document("_id", "old2").append("tenantId", "a").append("createdAt", old),
                        new Document("_id", "pending")
                                .append("tenantId", "a")
                                .append("createdAt", old)
                                .append("_events", List.of(new Document("eventId", "e"))),
                        new Document("_id", "foreign").append("tenantId", "b").append("createdAt", old),
                        new Document("_id", "new").append("tenantId", "a").append("createdAt", new Date())));
                var retention = new MongoLogRetention(mongo, "ai_requests");
                var cutoff = Instant.parse("2021-01-01T00:00:00Z");
                assertThat(retention.purge("a", cutoff, true, 1)).isEqualTo(1);
                assertThat(logs.countDocuments()).isEqualTo(5);
                assertThat(retention.purge("a", cutoff, false, 1)).isEqualTo(1);
                assertThat(retention.purge("a", cutoff, false, 1)).isEqualTo(1);
                assertThat(retention.purge("a", cutoff, false, 1)).isZero();
                assertThat(logs.find().map(d -> d.getString("_id")).into(new java.util.ArrayList<>()))
                        .containsExactlyInAnyOrder("pending", "foreign", "new");
                assertThatThrownBy(() -> retention.purge("", cutoff, false, 10000))
                        .isInstanceOf(IllegalArgumentException.class);
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
