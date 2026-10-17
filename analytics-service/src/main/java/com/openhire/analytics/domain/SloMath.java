package com.openhire.analytics.domain;

/**
 * Status and error budget of an objective. The budget is the room between the target and perfect; what is left of it
 * decides the status: breached when the target is missed, at risk when less than a quarter of the budget remains.
 */
public final class SloMath {
    public static final String MET = "MET";
    public static final String AT_RISK = "AT_RISK";
    public static final String BREACHED = "BREACHED";
    static final double AT_RISK_BELOW = 25.0;

    private SloMath() {}

    public record Evaluation(String status, double errorBudgetRemainingPct) {}

    /** For a share of good events (percent) that must be at least the target, for example 99.5 percent available. */
    public static Evaluation atLeast(double currentPct, double targetPct) {
        double allowedBad = 100.0 - targetPct;
        double bad = 100.0 - currentPct;
        double remaining = allowedBad <= 0 ? (bad <= 0 ? 100 : 0) : 100.0 * (1 - bad / allowedBad);
        return evaluate(currentPct >= targetPct, remaining);
    }

    /** For a value that must stay at or below the target, for example a mean latency in milliseconds. */
    public static Evaluation atMost(double current, double target) {
        double remaining = target <= 0 ? 0 : 100.0 * (1 - current / target);
        return evaluate(current <= target, remaining);
    }

    private static Evaluation evaluate(boolean targetMet, double remaining) {
        double clamped = Stats.round(Math.max(0, Math.min(100, remaining)), 1);
        if (!targetMet) return new Evaluation(BREACHED, 0);
        return new Evaluation(clamped < AT_RISK_BELOW ? AT_RISK : MET, clamped);
    }
}
