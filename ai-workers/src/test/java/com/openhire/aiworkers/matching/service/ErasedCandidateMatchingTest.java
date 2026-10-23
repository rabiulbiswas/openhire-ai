package com.openhire.aiworkers.matching.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.openhire.aiworkers.matching.domain.Lexical;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.aiworkers.matching.domain.Taxonomy;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AiGateway;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AssessmentStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.ChunkStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.CriterionVectors;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.RetryStore;
import com.openhire.aiworkers.shared.service.port.ResumeSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErasedCandidateMatchingTest {
    @Test
    void aLateMatchingCommandForAnErasedCandidateIsSkipped() {
        var assessments = mock(AssessmentStore.class);
        var chunks = mock(ChunkStore.class);
        var retries = mock(RetryStore.class);
        var resumes = mock(ResumeSource.class);
        var gateway = mock(AiGateway.class);
        var service = new MatchingService(
                gateway,
                resumes,
                chunks,
                mock(CriterionVectors.class),
                assessments,
                retries,
                new Lexical(Taxonomy.builtIn()),
                new MatchingSettings(1, List.of(Duration.ofSeconds(60)), Duration.ofDays(3), Duration.ofMinutes(10)),
                Clock.systemUTC());
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-app-1", "req-1");
        service.setTombstones(tombstones);
        var request = MatchingRequest.fromEnvelope(
                Map.of("eventId", "evt-1", "tenantId", "t1", "payload", MatchingRequestFixtures.payload("app-1")));

        service.handle(request);

        verifyNoInteractions(assessments, chunks, retries, resumes, gateway);
    }
}
