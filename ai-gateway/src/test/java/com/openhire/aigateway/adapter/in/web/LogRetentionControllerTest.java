package com.openhire.aigateway.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.aigateway.application.port.LogRetention;
import com.openhire.support.Access;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LogRetentionControllerTest {
    @Test
    void authenticatesBeforeAccessAndRejectsUnsafeLiveCutoffs() {
        var logs = mock(LogRetention.class);
        var now = Instant.parse("2026-10-15T12:00:00Z");
        var controller = new LogRetentionController(
                logs, new Access("oidc", "demo", "secret"), Clock.fixed(now, ZoneOffset.UTC));
        var input = new LogRetentionController.Input("t", now.toString(), false, 50);
        assertThatThrownBy(() -> controller.run("wrong", input))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.run("secret", input)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.run("secret", new LogRetentionController.Input("t", "invalid", false, 50)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(logs);
        controller.run("secret", new LogRetentionController.Input("t", now.toString(), true, 50));
        verify(logs).purge("t", now, true, 50);
    }
}
