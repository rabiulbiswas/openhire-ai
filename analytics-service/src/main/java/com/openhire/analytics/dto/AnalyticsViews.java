package com.openhire.analytics.dto;

import java.util.List;

/** Response shapes of the analytics APIs; they match the console's api-client types. */
public final class AnalyticsViews {
    private AnalyticsViews() {}

    public record AnalyticsKpis(
            double medianTimeToHireDays,
            String medianTimeToHireDelta,
            double offerAcceptancePct,
            String offerAcceptanceDelta,
            double aiShortlistAgreementPct,
            String aiShortlistAgreementDelta,
            double aiCostPerScreenedApplicant,
            String aiCostDelta,
            String currency) {}

    public record FunnelStage(String label, long value) {}

    public record FunnelResult(String jobTitle, List<FunnelStage> stages) {}

    public record TimeToHirePoint(String x, double y) {}

    public record SourceQuality(String source, long applicants, double shortlistRatePct, long hires) {}

    public record OverrideReason(String label, long value) {}

    public record StageLatency(String stage, double medianHours, double p90Hours) {}
}
