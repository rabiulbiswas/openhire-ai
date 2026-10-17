package com.openhire.analytics.application;

import com.openhire.analytics.application.PlatformViews.Slo;
import com.openhire.analytics.domain.SloMath;
import java.util.List;
import java.util.Map;

/** Prometheus-backed reads, with an explicitly labelled local fallback rather than fabricated availability. */
public class MonitoredSlos {
    public interface Samples {
        Map<String, Double> current(String tenant);
    }

    private final PlatformQueryService platform;
    private final Samples samples;

    public MonitoredSlos(PlatformQueryService platform, Samples samples) {
        this.platform = platform;
        this.samples = samples;
    }

    public List<Slo> read(String tenant) {
        var observed = samples.current(tenant);
        return platform.slo(tenant).stream()
                .map(s -> {
                    var current = observed.get(s.id());
                    if (current == null)
                        return new Slo(
                                s.id(),
                                s.label(),
                                s.target(),
                                s.window(),
                                s.current(),
                                s.unit(),
                                s.status(),
                                s.errorBudgetRemainingPct(),
                                "Local fallback (Prometheus unavailable or no fresh sample): " + s.basis(),
                                s.approximate());
                    var evaluation = "ms".equals(s.unit())
                            ? SloMath.atMost(current, s.target())
                            : SloMath.atLeast(current, s.target());
                    return new Slo(
                            s.id(),
                            s.label(),
                            s.target(),
                            s.window(),
                            current,
                            s.unit(),
                            evaluation.status(),
                            evaluation.errorBudgetRemainingPct(),
                            "Prometheus snapshot of rolling " + s.window() + " indicator; " + s.basis(),
                            s.approximate());
                })
                .toList();
    }
}
