package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.platform.audit.AuditRecorder;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class JobCategoriesTest {
    @Test
    void tenantCatalogValidationHistoryDeletionAndOptimisticUpdates() {
        String database = "oh_category_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var service = new JobCategories(mongo, mock(AuditRecorder.class));
                service.indexes();
                assertThatThrownBy(() -> service.resolve("t", null, null, null, true))
                        .hasMessageContaining("required");
                assertThat(service.resolve("t", null, null, null, false)).isNull();
                assertThatThrownBy(() -> service.save("t", "a", null, "  ", true, 0))
                        .isInstanceOf(IllegalArgumentException.class);
                var it = service.save("t", "a", null, " IT / Software ", true, 0);
                assertThatThrownBy(() -> service.save("t", "a", null, "it / software", true, 0))
                        .isInstanceOf(IllegalStateException.class);
                service.save("other", "a", null, "IT / Software", true, 0);
                assertThat(service.list("t", true, 0, 1).total()).isEqualTo(1);
                assertThatThrownBy(() -> service.resolve("other", it.id(), "REMOTE", null, true))
                        .hasMessageContaining("not found");
                assertThatThrownBy(() -> service.resolve("t", it.id(), "ANYWHERE", null, true))
                        .isInstanceOf(IllegalArgumentException.class);
                var snapshot = service.resolve("t", it.id(), "HYBRID", null, true);
                mongo.getCollection("jobs")
                        .insertOne(new Document("tenantId", "t")
                                .append(
                                        "versions",
                                        java.util.List.of(
                                                new Document("classification", new Document("categoryId", it.id())))));
                service.save("t", "a", it.id(), "Technology", true, 1);
                assertThat(service.resolve("t", it.id(), "REMOTE", snapshot, false)
                                .categoryName())
                        .isEqualTo("IT / Software");
                assertThatThrownBy(() -> service.save("t", "a", it.id(), "Stale", true, 1))
                        .isInstanceOf(IllegalStateException.class);
                service.delete("t", "a", it.id(), 2);
                assertThat(service.list("t", true, 0, 20).data()).isEmpty();
                assertThat(service.list("t", false, 0, 20).data()).hasSize(1);
                assertThatThrownBy(() -> service.resolve("t", it.id(), "REMOTE", null, true))
                        .hasMessageContaining("active");
                assertThat(service.resolve("t", it.id(), "REMOTE", snapshot, false)
                                .categoryName())
                        .isEqualTo("IT / Software");
                var unused = service.save("t", "a", null, "Finance", true, 0);
                service.delete("t", "a", unused.id(), 1);
                assertThat(service.save("t", "a", null, "Finance", true, 0).id())
                        .isNotEqualTo(unused.id());
                assertThatThrownBy(() -> service.list("t", true, -1, 100)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
