package com.openhire.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class PlatformViews {
    private PlatformViews() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlatformService(
            String name,
            String status,
            double uptimePct24h,
            String lastCheckedAt,
            long responseMs,
            Long requests24h,
            Long errors24h) {}

    public record Slo(
            String id,
            String label,
            double target,
            String window,
            double current,
            String unit,
            String status,
            double errorBudgetRemainingPct,
            String basis,
            boolean approximate) {}

    public record ConsumerLag(String group, String topic, long lag, String status) {}
}
