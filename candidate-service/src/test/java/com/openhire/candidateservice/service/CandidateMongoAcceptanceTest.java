package com.openhire.candidateservice.service;

import static org.assertj.core.api.Assertions.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

/** Opt-in: creates and drops only a uniquely named oh_phase3_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class CandidateMongoAcceptanceTest {
    private static MongoClient client;
    private static MongoTemplate mongo;
    private static CandidateService service;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase3_test_" + UUID.randomUUID().toString().replace("-", ""));
        service = new CandidateService(
                mongo, new com.openhire.platform.audit.AuditRecorder(null, null, "candidate-service", false));
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    private Document request(String tenant, String email) {
        return new Document("tenantId", tenant)
                .append("email", email)
                .append("name", "Shared Name")
                .append("source", "RECRUITER")
                .append("applicationId", "app_1");
    }

    private Document projection(String tenant, String candidate, String app, long revision, String stage) {
        return new Document("tenantId", tenant)
                .append("candidateId", candidate)
                .append("applicationId", app)
                .append("revision", revision)
                .append("stage", stage)
                .append("jobId", "job")
                .append("jobTitle", "Engineer")
                .append("appliedAt", "2026-10-08T00:00:00Z");
    }

    @Test
    void resolvesIdenticalEmailWithinTenantButNeverAcrossTenants() {
        String tenant = UUID.randomUUID().toString();
        var one = service.resolve(request(tenant, "PERSON@example.test"));
        var replay = service.resolve(request(tenant, "person@example.test"));
        var other = service.resolve(request(tenant + "-other", "person@example.test"));
        assertThat(one.getString("id")).isEqualTo(replay.getString("id")).isNotEqualTo(other.getString("id"));
    }

    @Test
    void reviewedMergePreservesBothApplicationsAndIgnoresStaleProjection() {
        String tenant = UUID.randomUUID().toString();
        String first = service.resolve(request(tenant, "one@example.test")).getString("id");
        String second = service.resolve(request(tenant, "two@example.test")).getString("id");
        service.project(projection(tenant, first, "app_one", 2, "SHORTLISTED"));
        service.project(projection(tenant, first, "app_one", 1, "APPLIED"));
        service.project(projection(tenant, second, "app_two", 1, "APPLIED"));
        var suggestion = service.suggestions(tenant).getFirst();
        var result = service.merge(tenant, "reviewer", suggestion.getString("id"), new Document("email", "b"));
        assertThat(result.getString("status")).isEqualTo("DONE");
        var profile = service.view(tenant, first);
        assertThat(profile.get("id")).isEqualTo(service.view(tenant, second).get("id"));
        assertThat((java.util.List<?>) profile.get("applications")).hasSize(2);
        var canonical = service.canonical(tenant, first);
        var applications = (Document) canonical.get("applications");
        assertThat(((Document) applications.get("app_one")).getString("stage")).isEqualTo("SHORTLISTED");
        assertThat(service.merge(tenant, "reviewer", suggestion.getString("id"), new Document("email", "b"))
                        .getString("status"))
                .isEqualTo("DONE");
        // Simulate process failure after status=DONE but before lock cleanup.
        mongo.updateFirst(
                Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(canonical.getString("_id"))),
                new Update().set("mergeLock", suggestion.getString("id")),
                "candidates");
        mongo.updateFirst(
                Query.query(Criteria.where("tenantId").is(tenant).and("_id").is(suggestion.getString("id"))),
                new Update().unset("locksReleased"),
                "candidate_merges");
        service.recoverMerges();
        assertThat(service.canonical(tenant, first)).doesNotContainKey("mergeLock");
    }
}
