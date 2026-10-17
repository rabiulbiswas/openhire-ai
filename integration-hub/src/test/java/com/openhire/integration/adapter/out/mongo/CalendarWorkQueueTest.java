package com.openhire.integration.adapter.out.mongo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClients;
import com.openhire.integration.application.*;
import com.openhire.integration.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_MONGO_TESTS", matches = "true")
class CalendarWorkQueueTest {
    @Test
    void durableCreateEditCancelAndConcurrentDesiredState() {
        String database = "oh_phase8_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000")) {
            try {
                var configs = mock(IntegrationService.Store.class);
                var calendars = mock(CalendarConnections.class);
                when(configs.find("t", "CONNECTOR", "c"))
                        .thenReturn(Optional.of(new IntegrationConfig(
                                "c",
                                "t",
                                "CONNECTOR",
                                "Calendar",
                                "GOOGLE_CALENDAR",
                                null,
                                "secret://t/google",
                                List.of(),
                                true,
                                1)));
                Instant now = Instant.parse("2026-10-15T00:00:00Z");
                var queue = new CalendarWorkQueue(
                        new MongoTemplate(client, database),
                        configs,
                        calendars,
                        JsonMapper.builder().build(),
                        Clock.fixed(now, ZoneOffset.UTC),
                        true,
                        mock(IntegrationBudget.class));
                queue.indexes();
                var event = new CalendarEvent(
                        "Interview",
                        now.plusSeconds(3600),
                        now.plusSeconds(7200),
                        "Asia/Kolkata",
                        List.of("panel@example.com"),
                        false);
                var edited = new CalendarEvent(
                        "Updated interview", event.start(), event.end(), "UTC", event.attendees(), false);
                queue.enqueue("t", "c", "round", "request001", 0L, event, false, null);
                assertEquals(
                        1L,
                        queue.enqueue("t", "c", "round", "request001", 0L, event, false, null)
                                .getLong("version"));
                assertThrows(
                        ResponseStatusException.class,
                        () -> queue.enqueue("t", "c", "round", "request001", 0L, edited, false, null));
                assertThrows(ResponseStatusException.class, () -> queue.get("other", "c", "round"));
                when(calendars.write(eq("t"), eq("c"), any(), isNull(), eq(event), eq(false)))
                        .thenAnswer(call -> {
                            queue.enqueue("t", "c", "round", "request002", 1L, edited, false, null);
                            return Map.of("id", "remote-id");
                        });
                queue.dispatch();
                var pending = queue.get("t", "c", "round");
                assertEquals("PENDING", pending.getString("status"));
                assertEquals("remote-id", pending.getString("remoteId"));
                when(calendars.write(eq("t"), eq("c"), any(), eq("remote-id"), eq(edited), eq(false)))
                        .thenReturn(Map.of("id", "remote-id"));
                queue.dispatch();
                assertEquals("SYNCED", queue.get("t", "c", "round").getString("status"));
                queue.enqueue("t", "c", "round", "request003", 2L, null, true, null);
                when(calendars.write(eq("t"), eq("c"), any(), eq("remote-id"), isNull(), eq(true)))
                        .thenReturn(Map.of());
                queue.dispatch();
                assertEquals("CANCELLED", queue.get("t", "c", "round").getString("status"));
                assertNull(queue.get("t", "c", "round").getString("remoteId"));
            } finally {
                client.getDatabase(database).drop();
            }
        }
    }
}
