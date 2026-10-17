package com.openhire.talent.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openhire.talent.application.ProfileProjector;
import com.openhire.talent.application.RediscoveryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TalentEventsListenerTest {
    private final ProfileProjector projector = mock(ProfileProjector.class);
    private final RediscoveryService rediscovery = mock(RediscoveryService.class);
    private final TalentEventsListener listener = new TalentEventsListener(projector, rediscovery, new ObjectMapper());

    @Test
    void workflowApplicationEventsReachTheProjectionAndLegacyOnesDoNot() {
        listener.onApplicationEvent(
                "{\"eventId\":\"e1\",\"producer\":\"phase3-workflow\",\"eventType\":\"ApplicationStageChanged\","
                        + "\"tenantId\":\"t1\",\"aggregateId\":\"app-1\",\"occurredAt\":\"2026-10-01T00:00:00Z\","
                        + "\"payload\":{\"from\":\"SHORTLISTED\",\"to\":\"INTERVIEWING\"}}");
        listener.onApplicationEvent(
                "{\"eventId\":\"e2\",\"producer\":\"legacy\",\"eventType\":\"ApplicationSubmitted\","
                        + "\"tenantId\":\"t1\",\"aggregateId\":\"app-2\"}");

        verify(projector)
                .onApplicationEvent(
                        eq("t1"),
                        eq("e1"),
                        eq("ApplicationStageChanged"),
                        eq("app-1"),
                        eq(Instant.parse("2026-10-01T00:00:00Z")),
                        eq(Map.of("from", "SHORTLISTED", "to", "INTERVIEWING")));
        verify(projector, never()).onApplicationEvent(any(), any(), any(), eq("app-2"), any(), any());
    }

    @Test
    void aCompletedAssessmentCarriesScoreStrengthsAndOnlyGroundedQuotes() {
        listener.onAssessmentEvent(
                "{\"eventType\":\"assessment.completed.v1\",\"tenantId\":\"t1\",\"occurredAt\":\"2026-10-01T00:00:00Z\","
                        + "\"payload\":{\"applicationId\":\"app-1\",\"status\":\"COMPLETE\",\"score\":81,\"strengths\":[\"Kafka at scale\"],"
                        + "\"criteria\":[{\"label\":\"Java\",\"grounded\":true,\"evidence\":[\"built payment services in Java\"]},"
                        + "{\"label\":\"Go\",\"grounded\":false,\"evidence\":[\"invented\"]}]}}");

        verify(projector)
                .onAssessment(
                        eq("t1"),
                        eq("app-1"),
                        eq("COMPLETE"),
                        eq(81),
                        eq(List.of("Strength noted: Kafka at scale", "Java: \"built payment services in Java\"")),
                        any());
    }

    @Test
    void employeeMergeAndJobPublishedEventsAreRouted() {
        listener.onEmployeeEvent("{\"eventType\":\"employee.created.v1\",\"tenantId\":\"t1\","
                + "\"payload\":{\"candidateId\":\"c1\",\"applicationId\":\"app-1\"}}");
        listener.onCandidateEvent("{\"eventType\":\"CandidateMerged\",\"tenantId\":\"t1\","
                + "\"payload\":{\"sourceCandidateId\":\"c2\",\"survivorCandidateId\":\"c1\"}}");
        listener.onJobEvent(
                "{\"eventId\":\"j-evt\",\"eventType\":\"job.published.v1\",\"tenantId\":\"t1\",\"aggregateId\":\"job-1\","
                        + "\"payload\":{\"jobId\":\"job-1\",\"status\":\"OPEN\"}}");
        listener.onJobEvent(
                "{\"eventId\":\"j2\",\"eventType\":\"job.paused.v1\",\"tenantId\":\"t1\",\"payload\":{\"jobId\":\"job-1\"}}");

        verify(projector).onEmployeeCreated("t1", "c1", "app-1");
        verify(projector).onCandidateMerged("t1", "c2", "c1");
        verify(rediscovery).onJobPublished("t1", "job-1", "j-evt");
        assertThat(TalentEventsListener.notes(Map.of())).isEmpty();
    }
}
