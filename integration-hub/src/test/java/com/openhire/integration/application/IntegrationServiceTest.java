package com.openhire.integration.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IntegrationServiceTest {
    private final IntegrationService.Store store = mock(IntegrationService.Store.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final IntegrationService service = new IntegrationService(store, audit);

    private IntegrationConfig save(String id, Long version) {
        return service.save(
                "tenant",
                "admin",
                "CONNECTOR",
                id,
                version,
                "Calendar",
                "GOOGLE_CALENDAR",
                null,
                "secret://tenant/calendar",
                List.of(),
                true);
    }

    @Test
    void createsVersionOneAndAudits() {
        when(store.save(any(), eq(0L))).thenReturn(true);
        assertEquals(1, save(null, null).version());
        verify(audit).record(any());
    }

    @Test
    void rejectsCrossTenantOrMissingUpdates() {
        when(store.find("tenant", "CONNECTOR", "id")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> save("id", 1L));
        verify(store, never()).save(any(), anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsStaleUpdatesWithoutSuccessAudit() {
        var existing = new IntegrationConfig(
                "id",
                "tenant",
                "CONNECTOR",
                "Calendar",
                "GOOGLE_CALENDAR",
                null,
                "secret://tenant/calendar",
                List.of(),
                true,
                2);
        when(store.find("tenant", "CONNECTOR", "id")).thenReturn(Optional.of(existing));
        when(store.save(any(), eq(1L))).thenReturn(false);
        assertThrows(IntegrationService.Conflict.class, () -> save("id", 1L));
        verifyNoInteractions(audit);
    }

    @Test
    void requiresVersionForUpdates() {
        assertThrows(IllegalArgumentException.class, () -> save("id", null));
        verifyNoInteractions(store, audit);
    }
}
