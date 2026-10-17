package com.openhire.offer.domain;

import com.openhire.offer.domain.OfferTypes.ApprovalStep;
import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.domain.OfferTypes.StepStatus;
import com.openhire.offer.domain.OfferTypes.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Compensation validation and the rule-generated approval chain. */
public final class CompensationPolicy {
    private CompensationPolicy() {}

    public static boolean withinBand(Terms terms, Band band) {
        return band == null
                || (band.currency().equals(terms.currency())
                        && terms.base().compareTo(band.min()) >= 0
                        && terms.base().compareTo(band.max()) <= 0);
    }

    /** Throws IllegalArgumentException with a message fit to show the user. */
    public static void validate(Terms terms, Band band, LocalDate today) {
        if (blank(terms.currency())) throw new IllegalArgumentException("Currency is required");
        if (terms.base() == null || terms.base().signum() <= 0)
            throw new IllegalArgumentException("Base pay must be greater than zero");
        if (terms.variable() == null || terms.variable().signum() < 0)
            throw new IllegalArgumentException("Variable pay cannot be negative");
        if (terms.bonus() == null || terms.bonus().signum() < 0)
            throw new IllegalArgumentException("Joining bonus cannot be negative");
        if (band != null) {
            if (!band.currency().equals(terms.currency()))
                throw new IllegalArgumentException("Currency must be " + band.currency() + " to match the job's band");
            if (terms.base().compareTo(band.min()) < 0 || terms.base().compareTo(band.max()) > 0)
                throw new IllegalArgumentException("Base pay " + plain(terms.base()) + " is outside the job's band ("
                        + plain(band.min()) + " to " + plain(band.max()) + ")");
        }
        var joining = date(terms.joiningDate(), "Joining date");
        var expiry = date(terms.expiryDate(), "Offer expiry date");
        if (!expiry.isAfter(today)) throw new IllegalArgumentException("The offer expiry date must be in the future");
        if (!joining.isAfter(today)) throw new IllegalArgumentException("The joining date must be in the future");
        if (blank(terms.designation())) throw new IllegalArgumentException("Designation is required");
    }

    /** Finance joins when base pay is above the band midpoint. */
    public static boolean financeRequired(Terms terms, Band band) {
        return band != null && terms.base().compareTo(band.midpoint()) > 0;
    }

    /** Compensation changes need a fresh approval; dates, location, title and the cover note do not. */
    public static boolean reapprovalRequired(Terms before, Terms after) {
        return !before.sameCompensation(after);
    }

    public static List<ApprovalStep> chain(Terms terms, Band band) {
        var steps = new ArrayList<ApprovalStep>();
        steps.add(step(1, "HIRING_MANAGER", "Hiring manager", StepStatus.CURRENT));
        steps.add(step(2, "HR_ADMIN", "HR head", StepStatus.PENDING));
        if (financeRequired(terms, band)) steps.add(step(3, "FINANCE", "Finance", StepStatus.PENDING));
        return steps;
    }

    public static String chainNote(Terms terms, Band band) {
        return financeRequired(terms, band) ? "Finance joined because base pay is above the band midpoint" : null;
    }

    private static ApprovalStep step(int seq, String role, String label, StepStatus status) {
        return new ApprovalStep("s" + seq, seq, role, label, status, null, null, null, null);
    }

    private static LocalDate date(String value, String label) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException(label + " must be a date such as 2026-11-02");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
