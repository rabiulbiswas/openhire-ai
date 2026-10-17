package com.openhire.analytics.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.analytics.application.ProjectionService;
import com.openhire.analytics.domain.Envelope;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class AnalyticsEventsListenerTest {
    private final ProjectionService projections = mock(ProjectionService.class);
    private final AnalyticsEventsListener listener =
            new AnalyticsEventsListener(projections, JsonMapper.builder().build());

    @Test
    void anEnvelopeIsPassedToTheProjectionOfItsTopic() {
        listener.onApplicationEvent(
                """
                {"eventId":"e1","producer":"phase3-workflow","eventType":"ApplicationStageChanged","eventVersion":1,
                "occurredAt":"2026-10-01T10:15:30Z","tenantId":"t1","aggregateType":"Application","aggregateId":"app-1",
                "payload":{"from":"APPLIED","to":"SHORTLISTED","actorId":"u1"}}
                """);

        var captor = ArgumentCaptor.forClass(Envelope.class);
        verify(projections).onApplicationEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("e1");
        assertThat(event.eventType()).isEqualTo("ApplicationStageChanged");
        assertThat(event.tenantId()).isEqualTo("t1");
        assertThat(event.aggregateId()).isEqualTo("app-1");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-01T10:15:30Z"));
        assertThat(event.text("to")).isEqualTo("SHORTLISTED");
    }

    @Test
    void eachTopicFeedsItsOwnProjection() {
        var message = "{\"eventId\":\"e\",\"eventType\":\"x\",\"tenantId\":\"t\",\"payload\":{}}";
        listener.onAssessmentEvent(message);
        listener.onOfferEvent(message);
        listener.onResumeEvent(message);
        listener.onCommunicationCommand(message);
        listener.onDeliveryEvent(message);
        listener.onAiEvent(message);

        verify(projections).onAssessmentEvent(any());
        verify(projections).onOfferEvent(any());
        verify(projections).onResumeEvent(any());
        verify(projections).onCommunicationCommand(any());
        verify(projections).onDeliveryEvent(any());
        verify(projections).onAiEvent(any());
        verify(projections, never()).onApplicationEvent(any());
    }

    @Test
    void messagesThatAreNotJsonEventsAreSkippedAndMissingFieldsTolerated() {
        listener.onApplicationEvent("not json at all");
        listener.onApplicationEvent("[1,2,3]");
        listener.onApplicationEvent("{\"eventType\":\"X\",\"occurredAt\":\"yesterday\"}");

        var captor = ArgumentCaptor.forClass(Envelope.class);
        verify(projections).onApplicationEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("X");
        assertThat(captor.getValue().occurredAt()).isNull();
        assertThat(captor.getValue().payload()).isEmpty();
    }

    @Test
    void aStorageFailureReachesTheErrorHandlerForRetry() {
        doThrow(new IllegalStateException("mongo down")).when(projections).onOfferEvent(any());

        assertThatThrownBy(() -> listener.onOfferEvent("{\"eventId\":\"e\",\"eventType\":\"offer.sent.v1\"}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
