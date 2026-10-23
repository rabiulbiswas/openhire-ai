package com.openhire.compliance.service;

import com.openhire.compliance.domain.Times;
import com.openhire.compliance.domain.Ulid;
import com.openhire.compliance.service.port.CompliancePorts.AuditSink;
import com.openhire.compliance.service.port.CompliancePorts.Events;
import com.openhire.compliance.service.port.CompliancePorts.HoldStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class LegalHolds {
    private final HoldStore store;
    private final AuditSink sink;
    private final Events events;
    private final Clock clock;
    private final Consumer<String> afterRelease;

    /** {@code afterRelease} gets the tenant id once a hold is released, so blocked erasures can resume. */
    public LegalHolds(HoldStore store, AuditSink sink, Events events, Clock clock, Consumer<String> afterRelease) {
        this.store = store;
        this.sink = sink;
        this.events = events;
        this.clock = clock;
        this.afterRelease = afterRelease;
    }

    public Map<String, Object> place(
            String tenantId, Actor actor, String candidateId, String jobId, String reason, String expiresAt) {
        var candidate = blankToNull(candidateId);
        var job = blankToNull(jobId);
        if (candidate == null && job == null) throw new IllegalArgumentException("candidateId or jobId is required");
        if (candidate != null && job != null)
            throw new IllegalArgumentException("A hold covers a candidate or a job, not both");
        var why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 1000) throw new IllegalArgumentException("reason is required (max 1000)");
        var expiry = Times.normalise(expiresAt);
        if (expiry != null && expiry.compareTo(Times.format(clock.instant())) <= 0)
            throw new IllegalArgumentException("expiresAt must be in the future");
        var hold = Maps.of(
                "_id", Ulid.next("hld"),
                "tenantId", tenantId,
                "candidateId", candidate,
                "jobId", job,
                "reason", why,
                "status", "ACTIVE",
                "placedBy", actor.id(),
                "placedAt", Times.format(clock.instant()),
                "expiresAt", expiry);
        store.insert(hold);
        sink.record(AuditRecord.of(tenantId, "LEGAL_HOLD_PLACED")
                .user(actor.id(), actor.name())
                .resource("LegalHold", Maps.text(hold, "_id"))
                .about(candidate, null)
                .detail("scope", candidate != null ? "CANDIDATE" : "JOB")
                .detail("jobId", job)
                .build());
        events.publish(
                tenantId,
                "LegalHold",
                Maps.text(hold, "_id"),
                "compliance.legal-hold.placed.v1",
                Maps.of("holdId", Maps.text(hold, "_id"), "candidateId", candidate, "jobId", job, "expiresAt", expiry));
        return view(hold);
    }

    public List<Map<String, Object>> list(String tenantId, String status) {
        var now = Times.format(clock.instant());
        return store.list(tenantId, null).stream()
                .map(h -> view(h, now))
                .filter(h -> status == null || status.isBlank() || status.equalsIgnoreCase(Maps.text(h, "status")))
                .toList();
    }

    public Map<String, Object> release(String tenantId, Actor actor, String id, String reason) {
        var why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 1000) throw new IllegalArgumentException("reason is required (max 1000)");
        var hold = store.find(tenantId, id).orElseThrow(() -> new ResourceNotFoundException("Legal hold not found"));
        if (!"ACTIVE".equals(Maps.text(hold, "status")))
            throw new IllegalStateException("The hold is already released");
        hold.put("status", "RELEASED");
        hold.put("releasedBy", actor.id());
        hold.put("releasedAt", Times.format(clock.instant()));
        hold.put("releaseReason", why);
        store.replace(hold);
        sink.record(AuditRecord.of(tenantId, "LEGAL_HOLD_RELEASED")
                .user(actor.id(), actor.name())
                .resource("LegalHold", id)
                .about(Maps.text(hold, "candidateId"), null)
                .detail("scope", Maps.text(hold, "candidateId") != null ? "CANDIDATE" : "JOB")
                .build());
        events.publish(
                tenantId,
                "LegalHold",
                id,
                "compliance.legal-hold.released.v1",
                Maps.of(
                        "holdId",
                        id,
                        "candidateId",
                        Maps.text(hold, "candidateId"),
                        "jobId",
                        Maps.text(hold, "jobId")));
        afterRelease.accept(tenantId);
        return view(hold);
    }

    public long activeCount(String tenantId) {
        var now = clock.instant().toString();
        return store.active(tenantId).stream()
                .filter(h -> HoldPolicy.isActive(h, now))
                .count();
    }

    private Map<String, Object> view(Map<String, Object> hold) {
        return view(hold, Times.format(clock.instant()));
    }

    /** An ACTIVE hold past its expiry reads as EXPIRED. */
    private static Map<String, Object> view(Map<String, Object> hold, String now) {
        var map = Maps.copy(hold);
        map.put("id", map.remove("_id"));
        map.remove("tenantId");
        var expires = Maps.text(hold, "expiresAt");
        if ("ACTIVE".equals(map.get("status")) && expires != null && expires.compareTo(now) <= 0)
            map.put("status", "EXPIRED");
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
