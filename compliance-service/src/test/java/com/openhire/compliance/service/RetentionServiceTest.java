package com.openhire.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RetentionServiceTest {
    private final Rig rig = new Rig();
    private final Actor officer = new Actor("u1", "Dev");

    @Test
    void recordRetentionHonoursPoliciesAndLegalHolds() {
        var result = rig.retention.run("t1", true, null, officer);
        var records = Maps.list(result.get("recordPolicies"));
        assertThat(records).hasSize(2);
        assertThat(records.toString()).contains("BLOCKED_BY_LEGAL_HOLD");
        rig.retention.update("t1", officer, "audit.events", 84, false);
        result = rig.retention.run("t1", true, null, officer);
        assertThat(Maps.list(result.get("recordPolicies"))).hasSize(1);
    }

    /** Candidate with one application that ended in the given stage and was last active at the given time. */
    private void candidate(String n, String stage, String lastActivity, String jobId) {
        var app = "app_" + n;
        rig.directory.add(app, "cand_" + n, "Name " + n, jobId, stage);
        rig.submitted(app, jobId, lastActivity);
        if (!stage.equals("APPLIED")) rig.stage(app, "APPLIED", stage, "rec_1", lastActivity);
    }

    @BeforeEach
    void data() {
        // now is 2026-10-15; non-hired cutoff 24 months is 2024-10-15, withdrawn cutoff 6 months is 2026-04-15
        candidate("old", "REJECTED", "2024-01-10T10:00:00Z", "job_1");
        candidate("recent", "REJECTED", "2026-06-01T10:00:00Z", "job_1");
        candidate("hired", "HIRED", "2023-01-10T10:00:00Z", "job_1");
        candidate("open", "SHORTLISTED", "2023-02-10T10:00:00Z", "job_1");
        candidate("held", "REJECTED", "2023-03-10T10:00:00Z", "job_hold");
        candidate("withdrew", "WITHDRAWN", "2026-02-01T10:00:00Z", "job_1");
        rig.holds.place("t1", officer, null, "job_hold", "Litigation", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> policy(Map<String, Object> result, String key) {
        return ((List<Map<String, Object>>) result.get("policies"))
                .stream().filter(p -> key.equals(p.get("key"))).findFirst().orElseThrow();
    }

    private static List<String> erased(Map<String, Object> policy) {
        return Maps.list(policy.get("wouldErase")).stream()
                .map(e -> (String) e.get("candidateId"))
                .toList();
    }

    @Test
    void defaultsAreListedAndEditableWithinTheirMinimums() {
        var all = rig.retention.list("t1");
        assertThat(all)
                .extracting(p -> p.get("key"))
                .containsExactly("candidate.non-hired", "candidate.withdrawn", "audit.events", "ai.request-logs");
        assertThat(all.getFirst()).containsEntry("retentionMonths", 24).containsEntry("action", "ERASE");

        var updated = rig.retention.update("t1", officer, "candidate.non-hired", 36, true);
        assertThat(updated).containsEntry("retentionMonths", 36);
        assertThat(rig.retention.list("t2").getFirst()).containsEntry("retentionMonths", 24);
        assertThatThrownBy(() -> rig.retention.update("t1", officer, "candidate.non-hired", 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rig.retention.update("t1", officer, "audit.events", 6, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDryRunListsWhoWouldBeErasedAndChangesNothing() {
        var result = rig.retention.run("t1", true, null, officer);

        assertThat(result).containsEntry("dryRun", true).doesNotContainKey("requestsCreated");
        var nonHired = policy(result, "candidate.non-hired");
        assertThat(erased(nonHired)).containsExactly("cand_old");
        assertThat(Maps.list(nonHired.get("protectedByHold"))).singleElement().satisfies(p -> assertThat(p)
                .containsEntry("candidateId", "cand_held")
                .containsKey("holdId"));
        assertThat(nonHired).containsEntry("skippedInProgress", 1);
        assertThat(erased(nonHired)).doesNotContain("cand_hired", "cand_held", "cand_open", "cand_recent");
        assertThat(rig.privacyStore.requests).isEmpty();
        assertThat(rig.sink.of("RETENTION_RUN")).isEmpty();
    }

    @Test
    void theWithdrawnPolicyUsesItsOwnShorterCutoffAndClaimsTheCandidate() {
        var result = rig.retention.run("t1", true, null, officer);

        assertThat(erased(policy(result, "candidate.withdrawn"))).containsExactly("cand_withdrew");
        assertThat(erased(policy(result, "candidate.non-hired"))).doesNotContain("cand_withdrew");
    }

    @Test
    void asOfMovesTheCutoffForDryRunsOnly() {
        var future = rig.retention.run("t1", true, "2030-01-01T00:00:00Z", officer);
        assertThat(future).containsEntry("evaluatedAt", "2030-01-01T00:00:00.000Z");
        assertThat(erased(policy(future, "candidate.non-hired"))).contains("cand_old", "cand_recent");
        assertThat(erased(policy(future, "candidate.non-hired"))).doesNotContain("cand_hired");

        var real = rig.retention.run("t1", false, "2030-01-01T00:00:00Z", officer);
        assertThat(real).containsEntry("dryRun", false).containsEntry("evaluatedAt", "2026-10-15T10:00:00.000Z");
        assertThat(erased(policy(real, "candidate.non-hired"))).containsExactly("cand_old");
    }

    @Test
    void aRealRunCreatesRetentionErasuresWithEvidenceAndAudits() {
        var result = rig.retention.run("t1", false, null, officer);

        assertThat(result).containsEntry("dryRun", false).containsEntry("requestsCreated", 2);
        var requests = rig.privacy.list("t1", null);
        assertThat(requests).hasSize(2).allSatisfy(r -> assertThat(r)
                .containsEntry("source", "RETENTION")
                .containsEntry("type", "ERASURE")
                .containsKey("retention"));
        var old = requests.stream()
                .filter(r -> "cand_old".equals(r.get("candidateId")))
                .findFirst()
                .orElseThrow();
        assertThat(Maps.map(old.get("retention")))
                .containsEntry("policy", "candidate.non-hired")
                .containsEntry("months", 24)
                .containsEntry("lastActivityAt", "2024-01-10T10:00:00.000Z");
        assertThat(rig.sink.of("RETENTION_RUN")).hasSize(1);
        assertThat(rig.retention.lastRun("t1")).containsKey("at");

        // running again does not duplicate open requests
        assertThat(rig.retention.run("t1", false, null, officer)).containsEntry("requestsCreated", 0);
    }

    @Test
    void anApplicationThatIsStillInProgressInTheLiveSystemIsNotErased() {
        rig.directory.add("app_old", "cand_old", "Name", "job_1", "INTERVIEWING");

        var result = rig.retention.run("t1", true, null, officer);

        assertThat(erased(policy(result, "candidate.non-hired"))).doesNotContain("cand_old");
        assertThat(policy(result, "candidate.non-hired")).containsEntry("skippedInProgress", 2);
    }

    @Test
    void unavailableDirectoryMeansNobodyIsErasedRatherThanGuessing() {
        rig.directory.down = true;
        var result = rig.retention.run("t1", true, null, officer);
        assertThat(erased(policy(result, "candidate.non-hired"))).isEmpty();
    }

    @Test
    void theBatchLimitCapsARealRunButNotADryRun() {
        for (int i = 0; i < 60; i++) candidate("bulk" + i, "REJECTED", "2023-01-01T10:00:00Z", "job_1");
        var dry = rig.retention.run("t1", true, null, officer);
        int listed = dry.get("policies") instanceof List<?> all
                ? all.stream().mapToInt(p -> erased(Maps.map(p)).size()).sum()
                : 0;
        assertThat(listed).isGreaterThanOrEqualTo(60);
        var real = rig.retention.run("t1", false, null, officer);
        assertThat(real).containsEntry("requestsCreated", 50);
    }

    @Test
    void disabledPoliciesAreNotEvaluated() {
        rig.retention.update("t1", officer, "candidate.non-hired", 24, false);
        rig.retention.update("t1", officer, "candidate.withdrawn", 6, false);
        assertThat(rig.retention.run("t1", true, null, officer)).containsEntry("policies", List.of());
    }
}
