package com.openhire.integration.client.http;

import static org.junit.jupiter.api.Assertions.*;

import com.openhire.integration.domain.CalendarEvent;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CalendarWriteProviderTest {
    final String stable = "a".repeat(64);
    final CalendarEvent event = new CalendarEvent(
            "Interview",
            Instant.parse("2026-10-20T10:00:00Z"),
            Instant.parse("2026-10-20T11:00:00Z"),
            "Asia/Kolkata",
            List.of("person@example.com"),
            true);

    @Test
    void googleDuplicateCreationReconcilesRatherThanCreatingAnotherEvent() {
        var calls = new AtomicInteger();
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            if (calls.getAndIncrement() == 0) {
                assertEquals("POST", request.method());
                return new CalendarHttpProvider.Response(409, "{}");
            }
            assertEquals("PATCH", request.method());
            assertTrue(request.uri().getPath().endsWith(stable));
            return new CalendarHttpProvider.Response(200, "{\"id\":\"remote\"}");
        });
        assertEquals(
                "remote",
                provider.write("GOOGLE_CALENDAR", "test-token", stable, null, event, false)
                        .get("id"));
        assertEquals(2, calls.get());
    }

    @Test
    void microsoftTransactionReceiptIsReconciledUsingReturnedId() {
        var calls = new AtomicInteger();
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            assertEquals(calls.getAndIncrement() == 0 ? "POST" : "PATCH", request.method());
            assertEquals("graph.microsoft.com", request.uri().getHost());
            return new CalendarHttpProvider.Response(200, "{\"id\":\"remote\"}");
        });
        provider.write("MICROSOFT_365", "test-token", stable, null, event, false);
        assertEquals(2, calls.get());
    }

    @Test
    void repeatedCancellationIsSuccessful() {
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            assertEquals("DELETE", request.method());
            return new CalendarHttpProvider.Response(404, "{}");
        });
        assertTrue(provider.write("GOOGLE_CALENDAR", "test-token", stable, "remote", null, true)
                .isEmpty());
    }

    @Test
    void cancelWithoutRemoteReceiptDoesNotCallProvider() {
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            throw new AssertionError("No call expected");
        });
        assertTrue(provider.write("MICROSOFT_365", "test-token", stable, null, null, true)
                .isEmpty());
    }
}
