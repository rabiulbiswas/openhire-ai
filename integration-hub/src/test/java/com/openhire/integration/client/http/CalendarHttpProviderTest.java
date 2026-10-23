package com.openhire.integration.client.http;

import static org.junit.jupiter.api.Assertions.*;

import com.openhire.integration.service.CalendarPorts.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class CalendarHttpProviderTest {
    final Credentials credentials =
            new Credentials("client", "secret", "http://localhost:5173/admin/connectors", "organizations");

    @Test
    void googleAuthorizationUsesOfflineAndPkce() {
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            throw new AssertionError("No network expected");
        });
        String url = provider.authorize("GOOGLE_CALENDAR", credentials, "state", "challenge");
        assertTrue(url.startsWith("https://accounts.google.com/"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertFalse(url.contains("client_secret"));
    }

    @Test
    void microsoftUsesFixedOrigin() {
        var provider = new CalendarHttpProvider(
                JsonMapper.builder().build(), request -> new CalendarHttpProvider.Response(200, "{}"));
        assertTrue(provider.authorize("MICROSOFT_365", credentials, "s", "c")
                .startsWith("https://login.microsoftonline.com/organizations/"));
    }

    @Test
    void exchangesTokenWithoutLiveKey() {
        var provider = new CalendarHttpProvider(JsonMapper.builder().build(), request -> {
            assertEquals("https://oauth2.googleapis.com/token", request.uri().toString());
            assertEquals("POST", request.method());
            return new CalendarHttpProvider.Response(
                    200,
                    "{\"access_token\":\"test-access\",\"refresh_token\":\"test-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        var grant = provider.exchange("GOOGLE_CALENDAR", credentials, "code", "verifier", Instant.EPOCH);
        assertEquals("test-access", grant.accessToken());
        assertEquals(Instant.EPOCH.plusSeconds(3600), grant.expiresAt());
    }

    @Test
    void providerErrorsNeverExposeResponseSecrets() {
        var provider = new CalendarHttpProvider(
                JsonMapper.builder().build(), request -> new CalendarHttpProvider.Response(400, "secret-token"));
        var error = assertThrows(
                ResponseStatusException.class,
                () -> provider.exchange("GOOGLE_CALENDAR", credentials, "code", "verifier", Instant.EPOCH));
        assertFalse(error.getMessage().contains("secret-token"));
    }

    @Test
    void missingCalendarScopeRejected() {
        var provider = new CalendarHttpProvider(
                JsonMapper.builder().build(),
                request -> new CalendarHttpProvider.Response(
                        200,
                        "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"openid\"}"));
        assertThrows(
                ResponseStatusException.class,
                () -> provider.exchange("MICROSOFT_365", credentials, "c", "v", Instant.EPOCH));
    }

    @Test
    void refreshKeepsPreviousRefreshTokenWhenNotRotated() {
        var provider = new CalendarHttpProvider(
                JsonMapper.builder().build(),
                request -> new CalendarHttpProvider.Response(
                        200, "{\"access_token\":\"new\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        assertEquals(
                "old-refresh",
                provider.refresh(
                                "MICROSOFT_365",
                                credentials,
                                new Grant("old", "old-refresh", Instant.EPOCH),
                                Instant.EPOCH)
                        .refreshToken());
    }
}
