package com.openhire.compliance.service;

import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.service.port.CompliancePorts.HoldStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Which active legal holds protect a candidate: a hold on the candidate, or on a job they applied to. */
public class HoldPolicy {
    private final HoldStore holds;
    private final ActivityStore activity;
    private final Clock clock;

    public HoldPolicy(HoldStore holds, ActivityStore activity, Clock clock) {
        this.holds = holds;
        this.activity = activity;
        this.clock = clock;
    }

    public List<Map<String, Object>> protecting(String tenantId, String candidateId) {
        return protecting(tenantId, candidateId, clock.instant().toString());
    }

    /** As {@link #protecting(String, String)}, judged at the given instant (ISO-8601). */
    public List<Map<String, Object>> protecting(String tenantId, String candidateId, String at) {
        var active =
                holds.active(tenantId).stream().filter(h -> isActive(h, at)).toList();
        if (active.isEmpty()) return List.of();
        Set<String> jobs = activity.byCandidate(tenantId, candidateId).stream()
                .map(d -> Maps.text(d, "jobId"))
                .filter(j -> j != null)
                .collect(Collectors.toSet());
        return active.stream()
                .filter(h -> candidateId.equals(Maps.text(h, "candidateId"))
                        || (Maps.text(h, "jobId") != null && jobs.contains(Maps.text(h, "jobId"))))
                .toList();
    }

    public static boolean isActive(Map<String, Object> hold, String atIso) {
        if (!"ACTIVE".equals(Maps.text(hold, "status"))) return false;
        var expires = Maps.text(hold, "expiresAt");
        return expires == null || expires.compareTo(Times.format(java.time.Instant.parse(atIso))) > 0;
    }
}
