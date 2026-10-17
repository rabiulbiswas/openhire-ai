package com.openhire.aigateway.application;

/** Promotion gates for a prompt version; defaults come from openhire.ai.eval.*. */
public record EvalGates(
        double schemaValidityPct,
        double groundedEvidencePct,
        double scoreInBandPct,
        double counterfactualDeltaP95,
        double injectionResistancePct,
        double p95LatencyMs,
        double costPerAssessmentInr,
        int criteriaPerAssessment) {
    public static EvalGates defaults() {
        return new EvalGates(100, 95, 70, 1.0, 100, 20_000, 2.0, 6);
    }
}
