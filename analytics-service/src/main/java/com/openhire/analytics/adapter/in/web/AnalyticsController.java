package com.openhire.analytics.adapter.in.web;

import com.openhire.analytics.application.AnalyticsQueryService;
import com.openhire.analytics.application.AnalyticsViews.AnalyticsKpis;
import com.openhire.analytics.application.AnalyticsViews.FunnelResult;
import com.openhire.analytics.application.AnalyticsViews.OverrideReason;
import com.openhire.analytics.application.AnalyticsViews.SourceQuality;
import com.openhire.analytics.application.AnalyticsViews.StageLatency;
import com.openhire.analytics.application.AnalyticsViews.TimeToHirePoint;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController {
    private final AnalyticsQueryService analytics;
    private final Viewers viewers;

    AnalyticsController(AnalyticsQueryService analytics, Viewers viewers) {
        this.analytics = analytics;
        this.viewers = viewers;
    }

    @GetMapping("/kpis")
    AnalyticsKpis kpis(
            Authentication auth,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) String jobId) {
        return analytics.kpis(viewers.analyticsTenant(auth), range, jobId);
    }

    @GetMapping("/funnel")
    FunnelResult funnel(
            Authentication auth,
            @RequestParam(required = false) String jobId,
            @RequestParam(defaultValue = "30d") String range) {
        return analytics.funnel(viewers.analyticsTenant(auth), jobId, range);
    }

    @GetMapping("/time-to-hire")
    List<TimeToHirePoint> timeToHire(
            Authentication auth,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) String jobId) {
        return analytics.timeToHire(viewers.analyticsTenant(auth), range, jobId);
    }

    @GetMapping("/sources")
    List<SourceQuality> sources(
            Authentication auth,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) String jobId) {
        return analytics.sources(viewers.analyticsTenant(auth), range, jobId);
    }

    @GetMapping("/ai-overrides")
    List<OverrideReason> aiOverrides(
            Authentication auth,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) String jobId) {
        return analytics.aiOverrides(viewers.analyticsTenant(auth), range, jobId);
    }

    @GetMapping("/stage-latency")
    List<StageLatency> stageLatency(
            Authentication auth,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) String jobId) {
        return analytics.stageLatency(viewers.analyticsTenant(auth), range, jobId);
    }
}
