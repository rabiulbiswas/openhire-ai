package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.jobservice.messaging.publisher.PublicationRecovery;
import com.openhire.jobservice.service.port.OutboxWriter;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

class PublicationRecoveryTest {
    @Test
    void recoveryKeysAreStableAndScopedToVersionAndTenant() {
        var at = Instant.now();
        var first = PublicationEvents.event("job.published.v1", "t", "j", 1, at);
        assertThat(PublicationEvents.event("job.published.v1", "t", "j", 1, at).eventId())
                .isEqualTo(first.eventId());
        assertThat(PublicationEvents.event("job.published.v1", "t", "j", 2, at).eventId())
                .isNotEqualTo(first.eventId());
        assertThat(PublicationEvents.event("job.published.v1", "other", "j", 1, at)
                        .eventId())
                .isNotEqualTo(first.eventId());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
    void publicationRecoverySurvivesOutboxFailureWithoutPublishingHistoricalJobs() {
        String database = "oh_publication_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var rows = mongo.getCollection("jobs");
                rows.insertOne(new Document("_id", "new-job")
                        .append("tenantId", "t")
                        .append("approvedVersion", 2)
                        .append("publicationPending", true)
                        .append("publishedAt", new Date()));
                rows.insertOne(new Document("_id", "historical")
                        .append("tenantId", "t")
                        .append("status", "PENDING_APPROVAL")
                        .append("approvedVersion", 1));
                var writer = mock(OutboxWriter.class);
                doThrow(new IllegalStateException("outbox unavailable"))
                        .doNothing()
                        .when(writer)
                        .append(anyString(), any());
                var recovery = new PublicationRecovery(mongo, writer);
                recovery.recover();
                assertThat(rows.find(new Document("_id", "new-job")).first().getBoolean("publicationPending"))
                        .isTrue();
                recovery.recover();
                assertThat(rows.find(new Document("_id", "new-job")).first().getBoolean("publicationPending"))
                        .isFalse();
                recovery.recover();
                verify(writer, times(3)).append(anyString(), any());
                assertThat(rows.find(new Document("_id", "historical")).first().getString("status"))
                        .isEqualTo("PENDING_APPROVAL");
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
