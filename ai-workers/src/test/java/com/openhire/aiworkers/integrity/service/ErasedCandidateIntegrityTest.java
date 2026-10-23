package com.openhire.aiworkers.integrity.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.openhire.aiworkers.AuditProbe;
import com.openhire.aiworkers.integrity.domain.Detector;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.ApplicationDirectory;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.ApplicationInfo;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.IdentityIndex;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.SignalStore;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.Structuring;
import com.openhire.aiworkers.shared.service.port.ResumeSource;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErasedCandidateIntegrityTest {
    @Test
    void aLateResumeEventForAnErasedCandidateRaisesNothingAndIndexesNothing() {
        var signals = mock(SignalStore.class);
        var index = mock(IdentityIndex.class);
        var resumes = mock(ResumeSource.class);
        ApplicationDirectory applications =
                (tenant, id) -> new ApplicationInfo("cand-1", "Erased candidate", "", "", "res-1");
        var service = new IntegrityService(
                List.<Detector>of(),
                resumes,
                applications,
                mock(Structuring.class),
                index,
                signals,
                Clock.systemUTC(),
                AuditProbe.working().recorder());
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        service.setTombstones(tombstones);

        service.analyze("t1", "app-1", "res-1");

        verifyNoInteractions(signals, index, resumes);
    }
}
