package com.openhire.interview.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openhire.interview.domain.SlotFinder;
import com.openhire.interview.service.port.InterviewPorts.ApplicationDirectory;
import com.openhire.interview.service.port.InterviewPorts.ApplicationInfo;
import com.openhire.interview.service.port.InterviewPorts.BookingLedger;
import com.openhire.interview.service.port.InterviewPorts.JobDirectory;
import com.openhire.interview.service.port.InterviewPorts.LoopRepository;
import com.openhire.interview.service.port.InterviewPorts.SchedulingTokens;
import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CreateLoopAfterErasureTest {
    @Test
    void noLoopIsPlannedForAnErasedCandidate() {
        var loops = mock(LoopRepository.class);
        when(loops.findByApplication("t1", "app-1")).thenReturn(Optional.empty());
        ApplicationDirectory applications = (tenant, id) ->
                new ApplicationInfo(id, "cand-1", "Erased candidate", "job-1", "Engineer", "INTERVIEWING");
        var service = new InterviewApplicationService(
                loops,
                mock(BookingLedger.class),
                mock(SlotHolds.class),
                applications,
                mock(JobDirectory.class),
                mock(SchedulingTokens.class),
                new InterviewSettings(
                        Duration.ofHours(24),
                        3,
                        Duration.ofMinutes(30),
                        Duration.ofDays(7),
                        "http://portal.test",
                        ZoneId.of("UTC"),
                        SlotFinder.Policy.standard()),
                Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), ZoneId.of("UTC")),
                new AuditProbe().recorder);
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        service.setTombstones(tombstones);

        assertThatThrownBy(() -> service.createLoop("t1", "app-1", "sub-r")).hasMessageContaining("erased");

        verify(loops, never()).save(any());
    }
}
