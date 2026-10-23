package com.openhire.integration.repository.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import com.openhire.integration.security.TokenCipher;
import com.openhire.integration.service.CalendarPorts.*;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class MongoCalendarStoreTest {
    @Test
    void encryptedTenantScopedSingleUseAndDisconnectRaces() {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            var mongo = new MongoTemplate(client, database);
            try {
                var store = new MongoCalendarStore(
                        mongo,
                        new TokenCipher(Base64.getEncoder().encodeToString(new byte[32])),
                        JsonMapper.builder().build());
                store.indexes();
                Instant now = Instant.now();
                store.pending(new Pending("state", "t", "admin", "c", 1, 0, "verifier", now.plusSeconds(600)));
                assertNull(store.consume("state", "other-tenant", "admin", now));
                assertNull(store.consume("state", "t", "other-admin", now));
                assertEquals(
                        "verifier", store.consume("state", "t", "admin", now).verifier());
                assertNull(store.consume("state", "t", "admin", now));
                var grant = new Grant("access-secret", "refresh-secret", now.plusSeconds(3600));
                assertTrue(store.save(new SavedGrant("t", "c", 1, 1, grant), 0));
                assertFalse(mongo.getCollection("oauth_grants")
                        .find()
                        .first()
                        .toJson()
                        .contains("access-secret"));
                assertNull(store.load("other", "c"));
                assertEquals(grant, store.load("t", "c").grant());
                store.disconnect("t", "c");
                assertNull(store.load("t", "c").grant());
                assertFalse(store.save(new SavedGrant("t", "c", 1, 2, grant), 1), "stale refresh cannot reconnect");
                assertFalse(
                        store.save(new SavedGrant("t", "c", 1, 1, grant), 0),
                        "old authorization cannot replace disconnect tombstone");
                assertTrue(
                        store.save(new SavedGrant("t", "c", 1, 3, grant), 2),
                        "fresh authorization after disconnect succeeds");
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
