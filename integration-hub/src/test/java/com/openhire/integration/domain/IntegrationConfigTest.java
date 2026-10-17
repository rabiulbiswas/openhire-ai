package com.openhire.integration.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IntegrationConfigTest {
    private IntegrationConfig webhook(String endpoint) {
        return new IntegrationConfig(
                "id",
                "tenant",
                "WEBHOOK",
                "Hiring",
                null,
                endpoint,
                "secret://tenant/webhook",
                List.of("interview.scheduled"),
                true,
                1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.com",
                "https://127.0.0.1",
                "https://host.LOCAL",
                "https://host.localhost",
                "https://user:password@example.com",
                "https://example.com?token=secret",
                "https://example.com#fragment",
                "https://example.com:8080"
            })
    void rejectsUnsafeEndpoints(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> webhook(endpoint));
    }

    @Test
    void webhookDoesNotClaimDeliveryIsActive() {
        assertEquals(
                "DELIVERY_NOT_ENABLED",
                webhook("https://hooks.example.com/events").status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GOOGLE_CALENDAR", "MICROSOFT_365"})
    void selectedProvidersRequireVerification(String provider) {
        var config = new IntegrationConfig(
                "id",
                "tenant",
                "CONNECTOR",
                "Calendar",
                provider,
                null,
                "secret://tenant/calendar",
                List.of(),
                true,
                1);
        assertEquals("PENDING_VERIFICATION", config.status());
    }

    @Test
    void rejectsRawSecrets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntegrationConfig(
                        "id",
                        "tenant",
                        "CONNECTOR",
                        "Calendar",
                        "GOOGLE_CALENDAR",
                        null,
                        "raw-access-token",
                        List.of(),
                        true,
                        1));
    }
}
