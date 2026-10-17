package com.openhire.offer.domain;

import static com.openhire.offer.domain.Fixtures.BAND;
import static com.openhire.offer.domain.Fixtures.terms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.offer.domain.OfferTypes.ApprovalStep;
import com.openhire.offer.domain.OfferTypes.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CompensationPolicyTest {
    private static final LocalDate TODAY = LocalDate.parse("2026-10-15");

    @Test
    void validTermsInsideTheBandPass() {
        CompensationPolicy.validate(terms("24"), BAND, TODAY);
        assertThat(CompensationPolicy.withinBand(terms("24"), BAND)).isTrue();
    }

    @Test
    void baseOutsideTheBandIsRejectedWithTheBandInTheMessage() {
        assertThatThrownBy(() -> CompensationPolicy.validate(terms("31"), BAND, TODAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the job's band")
                .hasMessageContaining("20 to 30");
        assertThat(CompensationPolicy.withinBand(terms("31"), BAND)).isFalse();
    }

    @Test
    void currencyMustMatchTheBand() {
        var usd = new Terms(
                "USD",
                new BigDecimal("24"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "2026-11-16",
                null,
                "",
                "",
                "2026-10-22",
                "Eng");
        assertThatThrownBy(() -> CompensationPolicy.validate(usd, BAND, TODAY))
                .hasMessageContaining("Currency must be INR");
    }

    @Test
    void nonPositiveOrNegativeAmountsAreRejected() {
        assertThatThrownBy(() -> CompensationPolicy.validate(terms("0"), null, TODAY))
                .hasMessageContaining("Base pay");
        var negative = new Terms(
                "INR",
                BigDecimal.TEN,
                new BigDecimal("-1"),
                BigDecimal.ZERO,
                "2026-11-16",
                null,
                "",
                "",
                "2026-10-22",
                "Eng");
        assertThatThrownBy(() -> CompensationPolicy.validate(negative, null, TODAY))
                .hasMessageContaining("Variable");
    }

    @Test
    void datesMustBeRealAndInTheFuture() {
        var past = new Terms(
                "INR",
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "2026-11-16",
                null,
                "",
                "",
                "2026-10-14",
                "Eng");
        assertThatThrownBy(() -> CompensationPolicy.validate(past, null, TODAY))
                .hasMessageContaining("expiry date must be in the future");
        var junk = new Terms(
                "INR", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "soon", null, "", "", "2026-10-22", "Eng");
        assertThatThrownBy(() -> CompensationPolicy.validate(junk, null, TODAY))
                .hasMessageContaining("Joining date must be a date");
        var early = new Terms(
                "INR",
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "2026-10-15",
                null,
                "",
                "",
                "2026-10-22",
                "Eng");
        assertThatThrownBy(() -> CompensationPolicy.validate(early, null, TODAY))
                .hasMessageContaining("joining date must be in the future");
    }

    @Test
    void financeJoinsOnlyAboveTheBandMidpoint() {
        assertThat(CompensationPolicy.chain(terms("25"), BAND))
                .extracting(ApprovalStep::role)
                .containsExactly("HIRING_MANAGER", "HR_ADMIN");
        assertThat(CompensationPolicy.chain(terms("26"), BAND))
                .extracting(ApprovalStep::role)
                .containsExactly("HIRING_MANAGER", "HR_ADMIN", "FINANCE");
        assertThat(CompensationPolicy.chainNote(terms("26"), BAND)).contains("above the band midpoint");
        assertThat(CompensationPolicy.chainNote(terms("25"), BAND)).isNull();
    }

    @Test
    void onlyCompensationChangesNeedReapproval() {
        var base = terms("24");
        var moved = new Terms(
                "INR",
                base.base(),
                base.variable(),
                base.bonus(),
                "2026-11-30",
                "asked",
                "Other",
                "Pune",
                "2026-10-25",
                "Staff");
        assertThat(CompensationPolicy.reapprovalRequired(base, moved)).isFalse();
        assertThat(CompensationPolicy.reapprovalRequired(base, terms("25"))).isTrue();
    }
}
