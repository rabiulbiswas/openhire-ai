package com.openhire.talent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.openhire.talent.domain.TalentTypes.ApplicationRef;
import com.openhire.talent.domain.TalentTypes.StageStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankingTest {
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private static TalentProfile profile(String id, Instant lastActivity, String stage, boolean interviewed) {
        var profile = TalentProfile.create("t1", id, id, lastActivity, 24);
        profile.recordApplication(new ApplicationRef("app-" + id, "job-0", "Engineer", stage, lastActivity, null));
        if (interviewed)
            profile.recordStep(
                    new StageStep("e-" + id, "app-" + id, "job-0", "SHORTLISTED", "INTERVIEWING", lastActivity));
        profile.applyContent(
                "r", null, List.of("java", "kafka"), List.of(), List.of(), 5.0, "s", List.of(), lastActivity);
        return profile;
    }

    @Test
    void theWeightsAreTheDocumentedOnesAndSumToOne() {
        assertThat(Ranking.SEMANTIC + Ranking.RULES + Ranking.RECENCY + Ranking.HISTORY)
                .isCloseTo(1.0, within(1e-9));
        assertThat(Ranking.score(1, 0, 0, 0)).isCloseTo(0.55, within(1e-9));
        assertThat(Ranking.score(0, 1, 0, 0)).isCloseTo(0.25, within(1e-9));
        assertThat(Ranking.score(0, 0, 1, 0)).isCloseTo(0.10, within(1e-9));
        assertThat(Ranking.score(0, 0, 0, 1)).isCloseTo(0.10, within(1e-9));
        assertThat(Ranking.score(0.8, 0.5, 0.25, 1)).isCloseTo(0.44 + 0.125 + 0.025 + 0.10, within(1e-9));
    }

    @Test
    void componentsAreClampedToTheUnitRange() {
        assertThat(Ranking.score(2, 2, 2, 2)).isCloseTo(1.0, within(1e-9));
        assertThat(Ranking.score(-1, -1, -1, -1)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void recencyHalvesEveryYear() {
        assertThat(Ranking.recency(NOW, NOW)).isCloseTo(1.0, within(1e-9));
        assertThat(Ranking.recency(NOW.minus(Duration.ofDays(365)), NOW)).isCloseTo(0.5, within(1e-3));
        assertThat(Ranking.recency(NOW.minus(Duration.ofDays(730)), NOW)).isCloseTo(0.25, within(1e-3));
    }

    @Test
    void ruleCoverageUsesSynonymsAndIsNeutralWhenTheJobListsNothing() {
        assertThat(Ranking.ruleCoverage(List.of("Java", "Apache Kafka", "Go"), List.of("java", "kafka")))
                .isCloseTo(2.0 / 3, within(1e-9));
        assertThat(Ranking.ruleCoverage(List.of(), List.of("java"))).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aSilverMedalistOutranksAnOtherwiseIdenticalCandidate() {
        var silver = profile("silver", NOW, "REJECTED", true);
        var plain = profile("plain", NOW, "REJECTED", false);

        var silverScore = Ranking.rank(silver, 0.7, List.of("java"), NOW);
        var plainScore = Ranking.rank(plain, 0.7, List.of("java"), NOW);

        assertThat(silverScore.history()).isEqualTo(1.0);
        assertThat(plainScore.history()).isEqualTo(0.0);
        assertThat(silverScore.score() - plainScore.score()).isCloseTo(Ranking.HISTORY, within(1e-9));
    }

    @Test
    void aMoreRecentCandidateOutranksAnOlderOne() {
        var fresh = profile("fresh", NOW.minus(Duration.ofDays(10)), "REJECTED", false);
        var stale = profile("stale", NOW.minus(Duration.ofDays(700)), "REJECTED", false);

        assertThat(Ranking.rank(fresh, 0.7, List.of("java"), NOW).score())
                .isGreaterThan(Ranking.rank(stale, 0.7, List.of("java"), NOW).score());
    }

    @Test
    void aHistoricalAssessmentScoreNeverChangesTheScoreForAnotherJob() {
        var low = profile("low", NOW, "REJECTED", false);
        var high = profile("high", NOW, "REJECTED", false);
        low.recordAssessment("app-low", 5, List.of());
        high.recordAssessment("app-high", 99, List.of());

        assertThat(Ranking.rank(low, 0.6, List.of("java"), NOW).score())
                .isEqualTo(Ranking.rank(high, 0.6, List.of("java"), NOW).score());
    }

    @Test
    void aHiredCandidateGetsNoHistoryBoost() {
        var hired = profile("hired", NOW, "HIRED", true);

        assertThat(Ranking.history(hired)).isEqualTo(0.0);
        assertThat(hired.silverMedalist()).isFalse();
    }
}
