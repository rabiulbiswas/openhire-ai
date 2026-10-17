package com.openhire.integration.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.client.MongoClients;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class IntegrationBudgetTest {
    @Test
    void atomicLimitDedupTenantIsolationAndWindowReset() throws Exception {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var mongo = new MongoTemplate(client, database);
                var clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
                var budget = new IntegrationBudget(mongo, clock, 3, 3, 3, 3);
                budget.indexes();
                budget.webhook("t", "same");
                budget.webhook("t", "same");
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = new ArrayList<Future<Boolean>>();
                    for (int i = 0; i < 20; i++) {
                        String id = "event" + i;
                        futures.add(executor.submit(() -> {
                            try {
                                budget.webhook("t", id);
                                return true;
                            } catch (ResponseStatusException denied) {
                                assertEquals(429, denied.getStatusCode().value());
                                return false;
                            }
                        }));
                    }
                    int accepted = 0;
                    for (var f : futures) if (f.get()) accepted++;
                    assertEquals(2, accepted);
                }
                budget.webhook("t", "same");
                budget.webhook("other", "new");
                var next = new IntegrationBudget(mongo, Clock.offset(clock, Duration.ofDays(1)), 3, 3, 3, 3);
                next.webhook("t", "new-day");
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
