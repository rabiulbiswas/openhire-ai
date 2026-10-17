package com.openhire.talent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ErasedCandidateProjectionTest {
    private final Memory memory = new Memory();
    private final ProfileProjector projector = memory.projector();

    @Test
    void lateEventsAndConsentForAnErasedCandidateRecreateNothing() {
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        projector.setTombstones(tombstones);
        memory.application("app-1", "cand-1", "Priya Das", "job-1", "Backend Engineer", "APPLIED", "res-1");
        memory.resumeText.put("res-1", "Priya Das, 8 years of Java");

        projector.onApplicationEvent("t1", "e1", "ApplicationScreened", "app-1", memory.clock.instant(), Map.of());
        projector.onEmployeeCreated("t1", "cand-1", "app-1");
        assertThatThrownBy(() -> projector.applyConsent("t1", "app-1", "cand-1", "Priya Das", true))
                .hasMessageContaining("erased");

        assertThat(memory.profileRows).isEmpty();
        assertThat(memory.vectorRows).isEmpty();
    }

    @Test
    void anotherCandidateIsProjectedAsUsual() {
        var tombstones = InMemoryMongo.tombstones();
        tombstones.mark("t1", "cand-1", "req-1");
        projector.setTombstones(tombstones);
        memory.application("app-2", "cand-2", "Ben Other", "job-1", "Backend Engineer", "APPLIED", "res-2");

        projector.onApplicationEvent("t1", "e2", "ApplicationScreened", "app-2", memory.clock.instant(), Map.of());

        assertThat(memory.profileRows).containsKey("t1:cand-2");
    }
}
