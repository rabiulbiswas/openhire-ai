package com.openhire.dispatch.messaging.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openhire.dispatch.service.DispatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DispatchCommandsListenerTest {
    private final DispatchService dispatch = mock(DispatchService.class);
    private final DispatchCommandsListener listener = new DispatchCommandsListener(
            dispatch, new ObjectMapper(), Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("UTC")));

    private static String command(String correlation) {
        return """
                {"eventType":"communication.message.requested.v1","tenantId":"t1","correlationId":"%s",
                 "payload":{"messageId":"msg_1","audience":"CANDIDATE","channel":"EMAIL","category":"TRANSACTIONAL",
                 "recipient":{"name":"Asha","email":"asha@example.test"},"subject":"Hi","body":"Hello"}}
                """
                .formatted(correlation);
    }

    @Test
    void aCommandForAnErasedCandidatesConversationIsDropped() {
        var tombstones = InMemoryMongo.tombstones();
        listener.setTombstones(tombstones);

        listener.onCommand(command("conv_abc"));
        verify(dispatch, times(1)).accept(any());

        tombstones.mark("t1", "conv_abc", "req-1");
        listener.onCommand(command("conv_abc"));
        listener.onCommand(command("conv_other"));

        verify(dispatch, times(2)).accept(any());
        verify(dispatch, never()).exhausted(any(), any());
    }
}
