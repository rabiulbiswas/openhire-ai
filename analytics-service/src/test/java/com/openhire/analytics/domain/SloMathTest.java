package com.openhire.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SloMathTest {
    @Test
    void shareOfGoodEventsIsMetAtRiskOrBreachedAgainstTheErrorBudget() {
        assertThat(SloMath.atLeast(100, 99.5)).isEqualTo(new SloMath.Evaluation("MET", 100));
        assertThat(SloMath.atLeast(99.8, 99.5)).isEqualTo(new SloMath.Evaluation("MET", 60));
        // A quarter of the budget left is still met; just below it is at risk.
        assertThat(SloMath.atLeast(99.625, 99.5)).isEqualTo(new SloMath.Evaluation("MET", 25));
        assertThat(SloMath.atLeast(99.6, 99.5)).isEqualTo(new SloMath.Evaluation("AT_RISK", 20));
        // Exactly on target spends the whole budget but does not breach.
        assertThat(SloMath.atLeast(99.5, 99.5)).isEqualTo(new SloMath.Evaluation("AT_RISK", 0));
        assertThat(SloMath.atLeast(99.4, 99.5)).isEqualTo(new SloMath.Evaluation("BREACHED", 0));
    }

    @Test
    void aPerfectTargetHasNoBudget() {
        assertThat(SloMath.atLeast(100, 100).status()).isEqualTo("MET");
        assertThat(SloMath.atLeast(99.9, 100).status()).isEqualTo("BREACHED");
    }

    @Test
    void valuesThatMustStayBelowATargetUseTheRoomLeft() {
        assertThat(SloMath.atMost(0, 500)).isEqualTo(new SloMath.Evaluation("MET", 100));
        assertThat(SloMath.atMost(375, 500)).isEqualTo(new SloMath.Evaluation("MET", 25));
        assertThat(SloMath.atMost(400, 500)).isEqualTo(new SloMath.Evaluation("AT_RISK", 20));
        assertThat(SloMath.atMost(500, 500)).isEqualTo(new SloMath.Evaluation("AT_RISK", 0));
        assertThat(SloMath.atMost(501, 500)).isEqualTo(new SloMath.Evaluation("BREACHED", 0));
    }
}
