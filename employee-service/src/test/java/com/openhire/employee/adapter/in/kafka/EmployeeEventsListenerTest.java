package com.openhire.employee.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.employee.application.HireSagaService;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class EmployeeEventsListenerTest {
    private final HireSagaService sagas = mock(HireSagaService.class);
    private final EmployeeEventsListener listener =
            new EmployeeEventsListener(sagas, JsonMapper.builder().build());

    @Test
    void offerAcceptedStartsTheSagaWithTheTermsFromThePayload() {
        listener.onOfferEvent(
                """
                {"eventType":"offer.accepted.v1","tenantId":"t1","payload":{"offerId":"off-1","applicationId":"app-1",
                "candidateId":"c1","candidateName":"Priya Das","jobId":"j1","jobTitle":"Engineer","ownerId":"o1",
                "terms":{"joiningDate":"2026-11-02","designation":"SE","location":"BLR","reportsTo":"Asha"}}}
                """);

        var captor = ArgumentCaptor.forClass(OfferAccepted.class);
        verify(sagas).start(captor.capture());
        assertThat(captor.getValue().offerId()).isEqualTo("off-1");
        assertThat(captor.getValue().joiningDate()).isEqualTo("2026-11-02");
        assertThat(captor.getValue().designation()).isEqualTo("SE");
    }

    @Test
    void otherOfferEventsAreIgnoredAndMalformedAcceptanceIsRejected() {
        listener.onOfferEvent("{\"eventType\":\"offer.sent.v1\",\"tenantId\":\"t1\",\"payload\":{}}");
        verify(sagas, never()).start(any());

        assertThatThrownBy(() -> listener.onOfferEvent(
                        "{\"eventType\":\"offer.accepted.v1\",\"tenantId\":\"t1\",\"payload\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAMoveToHiredReachesTheSaga() {
        listener.onApplicationEvent(
                "{\"eventType\":\"ApplicationStageChanged\",\"tenantId\":\"t1\",\"aggregateId\":\"app-1\",\"payload\":{\"from\":\"DECISION_PENDING\",\"to\":\"OFFER\"}}");
        verify(sagas, never()).applicationHired(any(), any());

        listener.onApplicationEvent(
                "{\"eventType\":\"ApplicationStageChanged\",\"tenantId\":\"t1\",\"aggregateId\":\"app-1\",\"payload\":{\"from\":\"OFFER\",\"to\":\"HIRED\"}}");
        verify(sagas).applicationHired("t1", "app-1");
    }

    @Test
    void hrisOutcomesAreRoutedByEventType() {
        listener.onIntegrationEvent(
                "{\"eventType\":\"integration.hris.synced.v1\",\"tenantId\":\"t1\",\"aggregateId\":\"x\",\"payload\":{\"employeeId\":\"e1\"}}");
        listener.onIntegrationEvent(
                "{\"eventType\":\"integration.hris.failed.v1\",\"tenantId\":\"t1\",\"aggregateId\":\"e2\",\"payload\":{}}");
        listener.onIntegrationEvent("{\"eventType\":\"integration.other.v1\",\"tenantId\":\"t1\"}");

        verify(sagas).hrisOutcome("t1", "e1", true);
        verify(sagas).hrisOutcome("t1", "e2", false);
    }
}
