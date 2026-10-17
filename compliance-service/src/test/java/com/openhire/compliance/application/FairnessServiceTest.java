package com.openhire.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.compliance.domain.Fairness;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FairnessServiceTest {
    private final Rig rig = new Rig();

    private static List<Fairness.Applicant> group(String name, int n, int reachedRank) {
        var out = new ArrayList<Fairness.Applicant>();
        for (int i = 0; i < n; i++) out.add(new Fairness.Applicant(name, reachedRank));
        return out;
    }

    private static Fairness.GroupResult result(List<Fairness.Applicant> all, String group) {
        return Fairness.analyse(all, 5, 0.8).getFirst().groups().stream()
                .filter(g -> g.group().equals(group))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void adverseImpactRatioIsTheGroupRateOverTheHighestRate() {
        var all = new ArrayList<Fairness.Applicant>();
        all.addAll(group("A", 10, 2)); // 10 of 10 shortlisted
        all.addAll(group("B", 10, 0)); // 0 of 10 shortlisted
        var five = new ArrayList<>(group("B", 4, 2));
        five.addAll(group("B", 6, 0));
        var mixed = new ArrayList<Fairness.Applicant>(group("A", 10, 2));
        mixed.addAll(five);

        var b = result(mixed, "B");
        assertThat(b.selectionRate()).isEqualTo(0.4);
        assertThat(b.adverseImpactRatio()).isEqualTo(0.4);
        assertThat(b.flag()).isTrue();
        assertThat(result(mixed, "A").flag()).isFalse();
        assertThat(result(mixed, "A").adverseImpactRatio()).isEqualTo(1.0);
        assertThat(result(all, "B").selected()).isZero();
        assertThat(result(all, "B").flag())
                .as("a group with zero selected is flagged")
                .isTrue();
    }

    @Test
    void ratiosAtOrAboveTheThresholdAreNotFlagged() {
        var all = new ArrayList<Fairness.Applicant>(group("A", 10, 2));
        all.addAll(group("B", 8, 2));
        all.addAll(group("B", 2, 0)); // 0.8 exactly
        assertThat(result(all, "B").adverseImpactRatio()).isEqualTo(0.8);
        assertThat(result(all, "B").flag()).isFalse();
    }

    @Test
    void smallGroupsAreShownButNeverFlaggedOrUsedAsTheBenchmark() {
        var all = new ArrayList<Fairness.Applicant>(group("A", 10, 2));
        all.addAll(group("A", 10, 0)); // 50%
        all.addAll(group("TINY", 3, 2)); // 100% but n < 5
        all.addAll(group("SMALL", 4, 0));
        var tiny = result(all, "TINY");
        assertThat(tiny.insufficientData()).isTrue();
        assertThat(tiny.flag()).isFalse();
        assertThat(result(all, "SMALL").flag()).isFalse();
        assertThat(result(all, "A").adverseImpactRatio())
                .as("the tiny group is not the benchmark")
                .isEqualTo(1.0);
    }

    @Test
    void onlyApplicantsWhoReachedTheEarlierStageCount() {
        var all = new ArrayList<Fairness.Applicant>(group("A", 5, 3)); // reached INTERVIEWING
        all.addAll(group("A", 5, 0));
        var transitions = Fairness.analyse(all, 5, 0.8);
        assertThat(transitions.get(1).groups().getFirst().n()).isEqualTo(5); // SHORTLISTED -> INTERVIEWING
        assertThat(transitions.get(1).groups().getFirst().selected()).isEqualTo(5);
        assertThat(transitions.get(3).groups()).isEmpty(); // nobody reached OFFER
    }

    private void applicant(String n, String gender, String furthestStage) {
        var app = "app_" + n;
        rig.directory.add(app, "cand_" + n, "N", "job_1", "APPLIED");
        rig.submitted(app, "job_1", "2026-09-01T10:00:00Z");
        for (var stage : List.of("AI_ASSESSED", "SHORTLISTED", "INTERVIEWING")) {
            rig.stage(app, "APPLIED", stage, "rec", "2026-09-02T10:00:00Z");
            if (stage.equals(furthestStage)) break;
        }
        rig.selfIdentification.put("t1", app, "receipt-" + app, Maps.of("gender", gender));
    }

    @Test
    void theReportIsBuiltFromEncryptedSelfIdentificationAndStageHistory() {
        for (int i = 0; i < 6; i++) applicant("m" + i, "MALE", "SHORTLISTED");
        for (int i = 0; i < 6; i++) applicant("f" + i, "female", i < 2 ? "SHORTLISTED" : "AI_ASSESSED");
        applicant("x", "PREFER_NOT_TO_SAY", "SHORTLISTED");

        var report = rig.fairness.report("t1", "job_1", "gender");

        assertThat(report).containsEntry("selfIdentifiedApplicants", 12).containsEntry("minGroupSize", 5);
        var first = Maps.list(report.get("transitions")).getFirst();
        var female = Maps.list(first.get("groups")).stream()
                .filter(g -> g.get("group").equals("FEMALE"))
                .findFirst()
                .orElseThrow();
        assertThat(female).containsEntry("n", 6).containsEntry("selected", 2).containsEntry("flag", true);
        assertThat(rig.selfIds.rows.values().toString())
                .doesNotContain("FEMALE")
                .doesNotContain("MALE\"");
        assertThat(rig.fairness.report("t2", "job_1", "gender")).containsEntry("selfIdentifiedApplicants", 0);
        assertThatThrownBy(() -> rig.fairness.report("t1", null, "religion"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void selfIdentificationNeedsAValidReceiptAndIsReturnedOnlyToTheCandidate() {
        applicant("m1", "MALE", "APPLIED");
        assertThat(rig.selfIdentification.get("t1", "app_m1", "receipt-app_m1")).containsEntry("gender", "MALE");
        assertThatThrownBy(() -> rig.selfIdentification.put("t1", "app_m1", "bad", Map.of("gender", "MALE")))
                .isInstanceOf(ComplianceExceptions.Forbidden.class);
        assertThatThrownBy(() ->
                        rig.selfIdentification.put("t1", "app_m1", "receipt-app_m1", Map.of("gender", "not valid!!")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theWeeklyRefreshAlertsOnlyForNewFlags() {
        for (int i = 0; i < 6; i++) applicant("m" + i, "MALE", "SHORTLISTED");
        for (int i = 0; i < 6; i++) applicant("f" + i, "FEMALE", "AI_ASSESSED");

        int first = rig.fairness.refreshAll();
        int second = rig.fairness.refreshAll();

        assertThat(first).isPositive();
        assertThat(second).isZero();
        assertThat(rig.events.of("compliance.fairness.alert.v1")).hasSize(first);
        assertThat(rig.events.of("compliance.fairness.alert.v1").getFirst().payload())
                .containsEntry("group", "FEMALE");
    }
}
