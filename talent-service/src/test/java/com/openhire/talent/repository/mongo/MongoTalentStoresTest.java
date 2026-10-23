package com.openhire.talent.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.openhire.talent.domain.TalentProfile;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Opt-in: creates and drops only a uniquely named oh_phase6_test_* database. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoTalentStoresTest {
    private static MongoClient client;
    private static MongoTemplate mongo;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(
                System.getenv().getOrDefault("OPENHIRE_TEST_MONGO_URI", "mongodb://localhost:27017"));
        mongo = new MongoTemplate(
                client, "oh_phase6_test_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterAll
    static void clean() {
        if (mongo != null) mongo.getDb().drop();
        if (client != null) client.close();
    }

    @Test
    void searchableProfilesRoundTripAndVectorsAreScopedByTenant() {
        var profiles = new MongoProfiles(mongo);
        var vectors = new MongoVectors(mongo);
        var now = Instant.parse("2026-10-15T00:00:00Z");
        var profile = TalentProfile.create("t1", "c1", "Priya", now, 24);
        profile.grant(now);
        profile.applyContent("r", null, List.of("java"), List.of(), List.of(), 5.0, "summary", List.of(), now);
        profiles.save(profile);
        vectors.save("t1", "c1", new float[] {1f, 2f});
        vectors.save("t2", "c9", new float[] {3f});

        assertThat(profiles.searchable("t1", now, false)).hasSize(1);
        assertThat(profiles.searchable("t2", now, false)).isEmpty();
        assertThat(vectors.all("t1")).containsOnlyKeys("c1");
        assertThat(vectors.all("t1").get("c1")).containsExactly(1f, 2f);
        vectors.delete("t1", "c1");
        assertThat(vectors.all("t1")).isEmpty();
    }
}
