package com.openhire.integration.repository.mongo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.integration.service.IntegrationService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class McpConfirmationsTest {
    @Test
    void approvalIsTenantActorVersionAndSingleUseBound() {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var configs = mock(IntegrationService.Store.class);
                var service = mock(IntegrationService.class);
                var config = new IntegrationConfig(
                        "c",
                        "t",
                        "CONNECTOR",
                        "Calendar",
                        "GOOGLE_CALENDAR",
                        null,
                        "secret://t/calendar",
                        List.of(),
                        true,
                        1);
                when(configs.find("t", "CONNECTOR", "c")).thenReturn(Optional.of(config));
                var mongo = new MongoTemplate(client, database);
                var clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
                var confirmations = new McpConfirmations(mongo, clock, service, configs);
                confirmations.indexes();
                String id = confirmations.prepare("t", "admin", "c");
                assertTrue(confirmations.pending("other", "admin").isEmpty());
                assertThrows(ResponseStatusException.class, () -> confirmations.execute("t", "admin", "c", id));
                assertThrows(ResponseStatusException.class, () -> confirmations.decide("t", "other-admin", id, true));
                confirmations.decide("t", "admin", id, true);
                confirmations.execute("t", "admin", "c", id);
                verify(service)
                        .save(
                                "t",
                                "admin",
                                "CONNECTOR",
                                "c",
                                1L,
                                "Calendar",
                                "GOOGLE_CALENDAR",
                                null,
                                "secret://t/calendar",
                                List.of(),
                                false);
                assertThrows(ResponseStatusException.class, () -> confirmations.execute("t", "admin", "c", id));
                String expires = confirmations.prepare("t", "admin", "c");
                var later = new McpConfirmations(mongo, Clock.offset(clock, Duration.ofMinutes(6)), service, configs);
                assertThrows(ResponseStatusException.class, () -> later.decide("t", "admin", expires, true));
                String stale = confirmations.prepare("t", "admin", "c");
                confirmations.decide("t", "admin", stale, true);
                when(configs.find("t", "CONNECTOR", "c"))
                        .thenReturn(Optional.of(new IntegrationConfig(
                                "c",
                                "t",
                                "CONNECTOR",
                                "Changed calendar",
                                "GOOGLE_CALENDAR",
                                null,
                                "secret://t/calendar",
                                List.of(),
                                true,
                                2)));
                assertThrows(ResponseStatusException.class, () -> confirmations.execute("t", "admin", "c", stale));
                verifyNoMoreInteractions(service);
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
