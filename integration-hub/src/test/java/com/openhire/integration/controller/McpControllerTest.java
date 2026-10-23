package com.openhire.integration.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.integration.repository.mongo.McpConfirmations;
import com.openhire.integration.service.IntegrationService;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class McpControllerTest {
    final McpAccess policy = mock(McpAccess.class);
    final Access access = mock(Access.class);
    final IntegrationService service = mock(IntegrationService.class);
    final McpConfirmations confirmations = mock(McpConfirmations.class);
    final Authentication auth = mock(Authentication.class);
    final McpController controller = new McpController(
            policy,
            access,
            service,
            confirmations,
            mock(AuditRecorder.class),
            JsonMapper.builder().build(),
            "https://console.example.com");

    McpControllerTest() {
        when(policy.require(auth, "integrations.read")).thenReturn("tenant");
        when(access.actor(auth)).thenReturn("admin");
    }

    @Test
    void rejectsForeignOriginBeforeExecutingAnyTool() {
        assertThrows(ResponseStatusException.class, () -> controller.call(auth, "https://evil.example", Map.of()));
        verifyNoInteractions(service, confirmations, policy);
    }

    @Test
    void invalidArgumentShapeCannotBecomeAnImplicitWrite() {
        var response = controller.call(
                auth,
                null,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", "connectors.disable", "arguments", "not-an-object")));
        assertTrue(((Map<?, ?>) response.getBody()).containsKey("error"));
        verifyNoInteractions(service, confirmations);
    }

    @Test
    void listIsPagedAndNeverReturnsSecretReferences() {
        var rows = new ArrayList<IntegrationConfig>();
        for (int i = 0; i < 100; i++)
            rows.add(new IntegrationConfig(
                    "c" + i,
                    "tenant",
                    "CONNECTOR",
                    "Calendar",
                    "GOOGLE_CALENDAR",
                    null,
                    "secret://tenant/private",
                    List.of(),
                    true,
                    1));
        when(service.page("tenant", "CONNECTOR", "previous", 100)).thenReturn(rows);
        var response = controller.call(
                auth,
                null,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        2,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", "connectors.list", "arguments", Map.of("after", "previous"))));
        var result = (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("result");
        var content = (Map<?, ?>) result.get("structuredContent");
        assertEquals("c99", content.get("nextCursor"));
        assertEquals(100, ((List<?>) content.get("connectors")).size());
        assertFalse(content.toString().contains("secret://"));
        verifyNoInteractions(confirmations);
    }

    @Test
    void writeWithoutConfirmationOnlyPreparesApproval() {
        when(confirmations.prepare("tenant", "admin", "c")).thenReturn("approval-id");
        var response = controller.call(
                auth,
                null,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        3,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", "connectors.disable", "arguments", Map.of("connectorId", "c"))));
        assertTrue(response.getBody().toString().contains("HUMAN_APPROVAL_REQUIRED"));
        verify(policy).require(auth, "integrations.write");
        verify(confirmations).prepare("tenant", "admin", "c");
        verifyNoMoreInteractions(confirmations);
        verifyNoInteractions(service);
    }
}
