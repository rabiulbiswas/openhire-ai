package com.openhire.aiworkers.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.openhire.aiworkers.matching.domain.MatchingRequest.Criterion;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CalibrationTest {
    private static Criterion criterion(String id, String requirement, double weight) {
        return new Criterion(id, id, requirement, weight, "");
    }

    private static Calibration.Input input(Criterion c, double lexical, double llm, boolean grounded) {
        return new Calibration.Input(c, lexical, llm, grounded, grounded ? List.of("quote") : List.of());
    }

    @Test
    void groundedCriteriaBlendTheJudgeAndLexicalCoverage() {
        assertThat(Calibration.criterionScore(true, 4, 1.0)).isCloseTo(0.86, within(1e-9));
        assertThat(Calibration.criterionScore(true, 5, 0)).isCloseTo(0.7, within(1e-9));
    }

    @Test
    void ungroundedCriteriaOnlyGetHalfOfTheLexicalCoverage() {
        assertThat(Calibration.criterionScore(false, 5, 1.0)).isCloseTo(0.5, within(1e-9));
        assertThat(Calibration.criterionScore(false, 5, 0)).isZero();
    }

    @Test
    void judgeScoresAreClampedToTheScale() {
        assertThat(Calibration.criterionScore(true, 9, 0)).isCloseTo(0.7, within(1e-9));
        assertThat(Calibration.criterionScore(true, -3, 0)).isZero();
    }

    @Test
    void overallScoreIsTheWeightNormalisedSum() {
        var a = criterion("a", "MUST", 3);
        var b = criterion("b", "NICE", 1);
        var result = Calibration.calibrate(List.of(input(a, 1, 5, true), input(b, 0, 0, false)), 1);

        // a: 0.7 + 0.3 = 1.0 (weight 0.75); b: 0 (weight 0.25)
        assertThat(result.score()).isEqualTo(75);
        assertThat(result.criteria())
                .extracting(Calibration.CriterionResult::score)
                .containsExactly(100, 0);
    }

    @Test
    void mustCriteriaBelowTheFloorAreHardGapsButNiceOnesAreNot() {
        var must = criterion("must", "MUST", 1);
        var nice = criterion("nice", "NICE", 1);
        var result = Calibration.calibrate(List.of(input(must, 0.5, 0, false), input(nice, 0, 0, false)), 1);

        // must: 0.5 * 0.5 = 0.25 < 0.4
        assertThat(result.hardGaps()).containsExactly("must");
        var exactlyAtFloor = Calibration.calibrate(List.of(input(must, 0.8, 0, false)), 1);
        assertThat(exactlyAtFloor.hardGaps()).isEmpty();
    }

    @Test
    void confidenceBandsFollowTheThresholds() {
        assertThat(Calibration.band(0.75)).isEqualTo("HIGH");
        assertThat(Calibration.band(0.7499)).isEqualTo("MEDIUM");
        assertThat(Calibration.band(0.5)).isEqualTo("MEDIUM");
        assertThat(Calibration.band(0.4999)).isEqualTo("LOW");
    }

    @Test
    void fullyGroundedAgreeingCompleteProfileIsHighConfidence() {
        var a = criterion("a", "MUST", 1);
        var b = criterion("b", "MUST", 1);
        var result = Calibration.calibrate(List.of(input(a, 0.8, 4, true), input(b, 1, 5, true)), 1);

        assertThat(result.confidence()).isEqualTo("HIGH");
        assertThat(result.anyGrounded()).isTrue();
    }

    @Test
    void nothingGroundedIsLowConfidenceAndNotAnyGrounded() {
        var result = Calibration.calibrate(List.of(input(criterion("a", "MUST", 1), 1, 5, false)), 1);

        assertThat(result.confidence()).isEqualTo("LOW");
        assertThat(result.anyGrounded()).isFalse();
    }

    @Test
    void lexicalOnlyUsesCoverageAndIsAlwaysLow() {
        var a = criterion("a", "MUST", 1);
        var b = criterion("b", "MUST", 1);
        var result = Calibration.lexicalOnly(List.of(a, b), List.of(1.0, 0.0));

        assertThat(result.score()).isEqualTo(50);
        assertThat(result.confidence()).isEqualTo("LOW");
        assertThat(result.hardGaps()).containsExactly("b");
    }

    @Test
    void completenessRewardsLengthAndSections() {
        assertThat(Calibration.completeness(0, Set.of())).isZero();
        assertThat(Calibration.completeness(500, Set.of("EXPERIENCE", "SKILLS", "EDUCATION")))
                .isEqualTo(1.0);
    }
}
