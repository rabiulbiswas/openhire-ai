package com.openhire.integration.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.integration.application.CalendarPorts.*;
import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.platform.audit.AuditRecorder;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CalendarConnectionsTest {
    final IntegrationService.Store configs = mock(IntegrationService.Store.class);
    final Store store = mock(Store.class);
    final Secrets secrets = mock(Secrets.class);
    final Provider provider = mock(Provider.class);
    final AuditRecorder audit = mock(AuditRecorder.class);
    final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    final CalendarConnections app =
            new CalendarConnections(configs, store, secrets, provider, Clock.fixed(now, ZoneOffset.UTC), audit);
    final Credentials credentials =
            new Credentials("id", "secret", "http://localhost:5173/admin/connectors", "organizations");

    @BeforeEach
    void setup() {
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
        when(secrets.credentials(any())).thenReturn(credentials);
    }

    @Test
    void pkceMatchesRfcVector() {
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                CalendarConnections.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void authorizationBindsTenantActorAndExpires() {
        when(provider.authorize(any(), any(), any(), any())).thenReturn("https://accounts.google.com/auth");
        app.authorize("t", "admin", "c");
        var captured = ArgumentCaptor.forClass(Pending.class);
        verify(store).pending(captured.capture());
        var pending = captured.getValue();
        assertEquals("t", pending.tenant());
        assertEquals("admin", pending.actor());
        assertEquals(now.plusSeconds(600), pending.expiresAt());
        assertEquals(43, pending.verifier().length());
        assertNotEquals(pending.state(), pending.verifier());
    }

    @Test
    void invalidOrReplayedStateNeverExchangesToken() {
        assertThrows(IllegalArgumentException.class, () -> app.complete("t", "admin", "state", "code"));
        verify(store).consume("state", "t", "admin", now);
        verifyNoInteractions(provider);
    }

    @Test
    void changedConfigurationRejectsCallback() {
        when(store.consume(any(), any(), any(), any()))
                .thenReturn(new Pending("s", "t", "admin", "c", 2, 0, "v", now.plusSeconds(60)));
        assertThrows(IntegrationService.Conflict.class, () -> app.complete("t", "admin", "s", "code"));
        verifyNoInteractions(provider);
    }

    @Test
    void completesAndAuditsWithoutReturningTokens() {
        when(store.consume(any(), any(), any(), any()))
                .thenReturn(new Pending("s", "t", "admin", "c", 1, 0, "v", now.plusSeconds(60)));
        when(provider.exchange(any(), any(), any(), any(), any()))
                .thenReturn(new Grant("access", "refresh", now.plusSeconds(3600)));
        when(store.save(any(), eq(0L))).thenReturn(true);
        app.complete("t", "admin", "s", "code");
        verify(audit).record(any());
    }

    @Test
    void refreshConflictDoesNotCallCalendar() {
        when(store.load("t", "c"))
                .thenReturn(new SavedGrant("t", "c", 1, 2, new Grant("old", "refresh", now.minusSeconds(1))));
        when(provider.refresh(any(), any(), any(), any()))
                .thenReturn(new Grant("new", "rotated", now.plusSeconds(3600)));
        assertThrows(IntegrationService.Conflict.class, () -> app.list("t", "c"));
        verify(provider, never()).list(any(), any());
    }

    @Test
    void unconfiguredConnectionIsNotReportedAuthorized() {
        assertEquals("PENDING_VERIFICATION", app.status("t", "c"));
    }
}
