package com.openhire.jobservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class PublicCareerQuestionsMongoTest {
    @Test
    void publicQuestionsRetainDistinctMappedAndLegacyIdsWithoutAnswerKeys() {
        try (var client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"))) {
            var mongo = new MongoTemplate(
                    client,
                    "oh_career_questions_test_" + UUID.randomUUID().toString().replace("-", ""));
            try {
                var questions = List.of(
                        new Document("_id", "java")
                                .append("question", "Java experience?")
                                .append("requiredAnswer", "Yes"),
                        new Document("_id", "systems")
                                .append("question", "Systems experience?")
                                .append("requiredAnswer", "Yes"),
                        new Document("id", "legacy")
                                .append("question", "Legacy question?")
                                .append("requiredAnswer", "No"));
                mongo.getCollection("jobs")
                        .insertOne(new Document("_id", "job")
                                .append("tenantId", "tenant")
                                .append("status", "OPEN")
                                .append("approvedVersion", 1)
                                .append("currentVersion", 1)
                                .append(
                                        "versions",
                                        List.of(new Document("version", 1).append("knockoutQuestions", questions))));
                var detail = new PublicCareerCatalog(mongo).detail("tenant", "job");
                assertThat(detail.getList("questions", Document.class))
                        .extracting(row -> row.getString("id"))
                        .containsExactly("java", "systems", "legacy");
                assertThat(detail.toJson()).doesNotContain("requiredAnswer");
            } finally {
                mongo.getDb().drop();
            }
        }
    }
}
