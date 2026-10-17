package com.openhire.aiworkers.integrity.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.aiworkers.integrity.application.IntegrityService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IntegrityListenerTest {
    private final IntegrityService integrity = mock(IntegrityService.class);
    private final IntegrityListener listener =
            new IntegrityListener(integrity, JsonMapper.builder().build());

    @Test
    void resumeProcessedRunsTheDetectorsForThatResume() {
        listener.onResumeEvent(
                """
                {"eventType":"ResumeProcessed","tenantId":"t1","aggregateId":"res-1","payload":{"applicationId":"app-1","status":"DONE"}}
                """);
        verify(integrity).analyze("t1", "app-1", "res-1");
    }

    @Test
    void otherResumeEventsAreIgnoredAndMalformedOnesRejected() {
        listener.onResumeEvent(
                "{\"eventType\":\"ResumeProcessingFailed\",\"tenantId\":\"t1\",\"aggregateId\":\"res-1\",\"payload\":{\"applicationId\":\"app-1\"}}");
        verify(integrity, never()).analyze(any(), any(), any());
        assertThatThrownBy(
                        () -> listener.onResumeEvent(
                                "{\"eventType\":\"ResumeProcessed\",\"tenantId\":\"t1\",\"aggregateId\":\"res-1\",\"payload\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
