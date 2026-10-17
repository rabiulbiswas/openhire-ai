package com.openhire.analytics.adapter.in.web;

import com.openhire.analytics.application.PlatformQueryService;
import com.openhire.support.Access;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** Low-cardinality exporter; never includes candidate, user, application or request IDs. */
@RestController
@RequestMapping("/api/v1/internal/platform")
class SloMetricsController {
    private final PlatformQueryService platform;
    private final Access access;
    private final List<String> tenants;

    SloMetricsController(
            PlatformQueryService platform,
            Access access,
            @Value("${openhire.analytics.metrics-tenants:demo-org}") String tenants) {
        this.platform = platform;
        this.access = access;
        this.tenants = Arrays.stream(tenants.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (this.tenants.size() > 100)
            throw new IllegalArgumentException("At most 100 metric tenants are supported locally");
    }

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    String metrics(@RequestHeader("X-Internal-Service-Secret") String secret) {
        access.internal(secret);
        var text = new StringBuilder(
                "# HELP openhire_slo_current Rolling-window SLO indicator.\n# TYPE openhire_slo_current gauge\n"
                        + "# HELP openhire_slo_error_budget_remaining_percent Remaining rolling-window error budget.\n# TYPE openhire_slo_error_budget_remaining_percent gauge\n");
        append(text, null);
        for (var tenant : tenants) append(text, tenant);
        return text.toString();
    }

    private void append(StringBuilder text, String tenant) {
        for (var slo : platform.slo(tenant)) {
            if (slo.basis().startsWith("No data")) continue;
            var labels = "{scope=\"" + (tenant == null ? "global" : "tenant") + "\",tenant_id=\""
                    + escape(tenant == null ? "" : tenant) + "\",slo_id=\"" + escape(slo.id()) + "\"}";
            text.append("openhire_slo_current")
                    .append(labels)
                    .append(' ')
                    .append(slo.current())
                    .append('\n');
            text.append("openhire_slo_error_budget_remaining_percent")
                    .append(labels)
                    .append(' ')
                    .append(slo.errorBudgetRemainingPct())
                    .append('\n');
        }
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
