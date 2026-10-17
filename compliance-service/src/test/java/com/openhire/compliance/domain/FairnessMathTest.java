package com.openhire.compliance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FairnessMathTest {
    @Test
    void roundingKeepsFourDecimals() {
        assertThat(Fairness.round(2.0 / 3.0)).isEqualTo(0.6667);
    }
}
