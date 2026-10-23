package com.openhire.talent.dto;

import com.openhire.talent.domain.TalentTypes.Filters;
import java.util.List;

public final class TalentViews {
    private TalentViews() {}

    public record CandidateView(
            String id,
            String name,
            String title,
            int matchScore,
            String whyFitNow,
            String historicalEvidence,
            boolean explained) {}

    /** The UI's TalentSearchResult: chips for display, the structured filter for editing and re-running. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SearchResult(
            List<String> filters,
            Filters filterSpec,
            String semanticQuery,
            List<CandidateView> candidates,
            String rediscoveryForJobTitle,
            String jobId,
            String semanticMode) {}

    public record PoolHealth(
            int silverMedalists, int consentExpiringSoon, int reEngagedThisQuarter, double hireRateFromPoolPct) {}

    public record ConsentView(boolean granted, String consentUpdatedAt) {}
}
