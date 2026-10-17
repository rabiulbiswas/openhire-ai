package com.openhire.aiworkers.matching.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.aiworkers.matching.application.MatchingService;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class MatchingCommandListenerTest {
    private final MatchingService matching = mock(MatchingService.class);
    private final MatchingCommandListener listener =
            new MatchingCommandListener(matching, JsonMapper.builder().build());

    @Test
    void parsesTheContractIntoARequest() {
        listener.onCommand(
                """
                {"eventId":"evt-1","eventType":"matching.requested.v1","tenantId":"t1","aggregateType":"Application","aggregateId":"app-1",
                 "payload":{"applicationId":"app-1","candidateId":"c1","candidateName":"Priya Das","resumeId":"res-1","jobId":"j1",
                 "jobTitle":"Engineer","jobVersion":4,
                 "rubric":[{"id":"r1","label":"Java","weight":30,"requirement":"MUST"}],
                 "mustHaveSkills":["Java"],
                 "knockoutQuestions":[{"id":"permit","question":"Permit?","requiredAnswer":"yes"}],
                 "answers":{"permit":"yes"},"eligibilityApproved":false}}
                """);

        var captor = ArgumentCaptor.forClass(MatchingRequest.class);
        verify(matching).handle(captor.capture());
        var request = captor.getValue();
        assertThat(request.eventId()).isEqualTo("evt-1");
        assertThat(request.jobVersion()).isEqualTo(4);
        assertThat(request.rubric().get(0).must()).isTrue();
        assertThat(request.failedKnockouts()).isEmpty();
    }

    @Test
    void otherEventTypesAreIgnoredAndMalformedCommandsRejected() {
        listener.onCommand("{\"eventId\":\"e\",\"eventType\":\"other.v1\",\"tenantId\":\"t1\",\"payload\":{}}");
        verify(matching, never()).handle(any());
        assertThatThrownBy(
                        () -> listener.onCommand(
                                "{\"eventId\":\"e\",\"eventType\":\"matching.requested.v1\",\"tenantId\":\"t1\",\"payload\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
