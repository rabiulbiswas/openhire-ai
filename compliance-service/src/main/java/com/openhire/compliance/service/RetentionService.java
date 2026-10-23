package com.openhire.compliance.service;

import com.openhire.compliance.domain.RetentionPolicy;
import com.openhire.compliance.domain.Stages;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.service.port.CompliancePorts.ApplicationDirectory;
import com.openhire.compliance.service.port.CompliancePorts.AuditSink;
import com.openhire.compliance.service.port.CompliancePorts.DirectoryUnavailable;
import com.openhire.compliance.service.port.CompliancePorts.PolicyStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Retention policies and their bounded execution. A candidate is erased (through a normal ERASURE
 * request) when all their activity is older than the policy period, none of their applications is in progress, they
 * were never hired, and no active legal hold protects them. Record policies create verified audit archive copies
 * and call the AI owner's bounded log purge; the source audit ledger is never deleted.
 */
public class RetentionService {
    private static final int SCAN_LIMIT = 2000;

    private final PolicyStore policies;
    private final ActivityStore activity;
    private final HoldPolicy holds;
    private final PrivacyService privacy;
    private final ApplicationDirectory directory;
    private final AuditSink sink;
    private final ComplianceSettings settings;
    private final Clock clock;
    private final com.openhire.compliance.service.port.CompliancePorts.RecordRetention records;
    private final com.openhire.compliance.service.port.CompliancePorts.HoldStore legalHolds;

    public RetentionService(
            PolicyStore policies,
            ActivityStore activity,
            HoldPolicy holds,
            PrivacyService privacy,
            ApplicationDirectory directory,
            AuditSink sink,
            ComplianceSettings settings,
            Clock clock,
            com.openhire.compliance.service.port.CompliancePorts.RecordRetention records,
            com.openhire.compliance.service.port.CompliancePorts.HoldStore legalHolds) {
        this.policies = policies;
        this.activity = activity;
        this.holds = holds;
        this.privacy = privacy;
        this.directory = directory;
        this.sink = sink;
        this.settings = settings;
        this.clock = clock;
        this.records = records;
        this.legalHolds = legalHolds;
    }

    public List<Map<String, Object>> list(String tenantId) {
        var overrides = new TreeMap<String, Map<String, Object>>();
        policies.overrides(tenantId).forEach(o -> overrides.put(Maps.text(o, "key"), o));
        var out = new ArrayList<Map<String, Object>>();
        for (var def : RetentionPolicy.DEFAULTS) {
            var o = overrides.getOrDefault(def.key(), Map.of());
            out.add(Maps.of(
                    "key", def.key(),
                    "label", def.label(),
                    "description", def.description(),
                    "retentionMonths",
                            o.get("retentionMonths") instanceof Number n ? n.intValue() : def.retentionMonths(),
                    "action", def.action(),
                    "enabled", o.get("enabled") instanceof Boolean b ? b : def.enabled(),
                    "lastRunAt", Maps.text(o, "lastRunAt"),
                    "lastRunSummary", Maps.text(o, "lastRunSummary")));
        }
        return out;
    }

    public Map<String, Object> update(String tenantId, Actor actor, String key, Integer months, Boolean enabled) {
        var def = RetentionPolicy.defaultFor(key);
        if (def == null) throw new ResourceNotFoundException("Unknown retention policy");
        if (months == null || months < def.minimumMonths())
            throw new IllegalArgumentException("retentionMonths must be at least " + def.minimumMonths());
        if (months > 1200) throw new IllegalArgumentException("retentionMonths is too large");
        var override = policies.overrides(tenantId).stream()
                .filter(o -> key.equals(Maps.text(o, "key")))
                .findFirst()
                .orElseGet(() -> Maps.of("_id", tenantId + "|" + key, "tenantId", tenantId, "key", key));
        override.put("retentionMonths", months);
        override.put("enabled", enabled == null ? def.enabled() : enabled);
        policies.save(override);
        sink.record(AuditRecord.of(tenantId, "RETENTION_POLICY_CHANGED")
                .user(actor.id(), actor.name())
                .resource("RetentionPolicy", key)
                .detail("retentionMonths", months)
                .detail("enabled", enabled == null ? def.enabled() : enabled)
                .build());
        return list(tenantId).stream()
                .filter(p -> key.equals(p.get("key")))
                .findFirst()
                .orElseThrow();
    }

    public Map<String, Object> lastRun(String tenantId) {
        return policies.overrides(tenantId).stream()
                .filter(o -> o.get("lastRunAt") != null)
                .max(Comparator.comparing(o -> Maps.text(o, "lastRunAt")))
                .map(o -> Maps.of("at", Maps.text(o, "lastRunAt"), "summary", Maps.text(o, "lastRunSummary")))
                .orElse(null);
    }

    /** The scheduled run: every tenant that has activity. */
    public void runAll() {
        var tenants = new java.util.TreeSet<>(activity.tenants());
        tenants.addAll(records.tenants());
        for (var tenant : tenants) run(tenant, false, null, Actor.SYSTEM);
    }

    /**
     * Evaluates the enabled candidate policies. A dry run changes nothing and may be judged as of another instant
     * ({@code asOf}, ignored for real runs).
     */
    public Map<String, Object> run(String tenantId, boolean dryRun, String asOf, Actor actor) {
        Instant now = dryRun && asOf != null && !asOf.isBlank() ? Instant.parse(asOf) : clock.instant();
        var nowIso = now.toString();
        var active = list(tenantId).stream()
                .filter(p -> ((String) p.get("key")).startsWith("candidate.") && Boolean.TRUE.equals(p.get("enabled")))
                .sorted(Comparator.comparing(
                        (Map<String, Object> p) -> RetentionPolicy.WITHDRAWN.equals(p.get("key")) ? 0 : 1))
                .toList();
        var results = new ArrayList<Map<String, Object>>();
        int created = 0, protectedTotal = 0, skippedTotal = 0;
        if (!active.isEmpty()) {
            var candidates = candidates(tenantId, now, active);
            var claimed = new HashSet<String>();
            // A dry run reports everyone affected; only a real run is held to the batch size.
            int budget = dryRun ? Integer.MAX_VALUE : settings.retentionBatch();
            for (var policy : active) {
                var key = (String) policy.get("key");
                int months = ((Number) policy.get("retentionMonths")).intValue();
                var cutoff = Times.format(Times.minusMonths(now, months));
                var wouldErase = new ArrayList<Map<String, Object>>();
                var protectedBy = new ArrayList<Map<String, Object>>();
                int skipped = 0;
                for (var entry : candidates.entrySet()) {
                    var candidate = entry.getKey();
                    var docs = entry.getValue();
                    if (claimed.contains(candidate)) continue;
                    var last = docs.stream()
                            .map(d -> Maps.text(d, "lastActivityAt", ""))
                            .max(String::compareTo)
                            .orElse("");
                    boolean hired = docs.stream()
                            .anyMatch(d ->
                                    "HIRED".equals(Maps.text(d, "lifecycle")) || Boolean.TRUE.equals(d.get("hired")));
                    if (hired) {
                        claimed.add(candidate);
                        continue;
                    }
                    if (last.compareTo(cutoff) >= 0) continue;
                    boolean allWithdrawn = docs.stream().allMatch(d -> "WITHDRAWN".equals(Maps.text(d, "lifecycle")));
                    if (key.equals(RetentionPolicy.WITHDRAWN) && !allWithdrawn) continue;
                    if (docs.stream().anyMatch(d -> "ACTIVE".equals(Maps.text(d, "lifecycle")))) {
                        skipped++;
                        claimed.add(candidate);
                        continue;
                    }
                    var protecting = holds.protecting(tenantId, candidate, nowIso);
                    if (!protecting.isEmpty()) {
                        for (var hold : protecting)
                            protectedBy.add(Maps.of("candidateId", candidate, "holdId", Maps.text(hold, "_id")));
                        claimed.add(candidate);
                        continue;
                    }
                    if (wouldErase.size() >= budget) continue;
                    switch (confirm(tenantId, docs)) {
                        case ERASABLE -> {
                            wouldErase.add(Maps.of("candidateId", candidate, "lastActivityAt", last));
                            claimed.add(candidate);
                        }
                        case IN_PROGRESS -> {
                            skipped++;
                            claimed.add(candidate);
                        }
                        case HIRED -> claimed.add(candidate);
                        case UNKNOWN -> {}
                    }
                }
                budget -= wouldErase.size();
                int madeHere = 0;
                if (!dryRun)
                    for (var item : wouldErase) {
                        var request = privacy.createByRetention(
                                tenantId,
                                Maps.text(item, "candidateId"),
                                "Retention policy " + key + " (" + months + " months)",
                                Maps.of(
                                        "policy", key,
                                        "months", months,
                                        "lastActivityAt", Maps.text(item, "lastActivityAt")));
                        if (request != null) madeHere++;
                    }
                created += madeHere;
                protectedTotal += protectedBy.size();
                skippedTotal += skipped;
                results.add(Maps.of(
                        "key", key,
                        "wouldErase", wouldErase,
                        "protectedByHold", protectedBy,
                        "skippedInProgress", skipped));
                if (!dryRun)
                    recordRun(
                            tenantId,
                            key,
                            Times.format(now),
                            madeHere + " erasure requests created, " + protectedBy.size() + " protected by hold, "
                                    + skipped + " skipped in progress");
            }
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("dryRun", dryRun);
        response.put("evaluatedAt", Times.format(now));
        response.put("policies", results);
        var recordResults = new ArrayList<Map<String, Object>>();
        for (var policy : list(tenantId)) {
            var key = Maps.text(policy, "key");
            if (!Boolean.TRUE.equals(policy.get("enabled"))
                    || !(RetentionPolicy.AUDIT.equals(key) || RetentionPolicy.AI_LOGS.equals(key))) continue;
            // AI request logs have no candidate mapping: any live hold conservatively protects the tenant's logs.
            boolean held = RetentionPolicy.AI_LOGS.equals(key)
                    && legalHolds.active(tenantId).stream()
                            .anyMatch(h -> Maps.text(h, "expiresAt") == null
                                    || Maps.text(h, "expiresAt").compareTo(Times.format(clock.instant())) > 0);
            Map<String, Object> result;
            if (held) result = Maps.of("key", key, "status", "BLOCKED_BY_LEGAL_HOLD", "processed", 0);
            else {
                try {
                    result = records.run(
                            tenantId,
                            key,
                            Times.format(Times.minusMonths(now, ((Number) policy.get("retentionMonths")).intValue())),
                            dryRun,
                            settings.retentionBatch());
                } catch (RuntimeException unavailable) {
                    result = Maps.of("key", key, "status", "FAILED", "processed", 0);
                }
            }
            recordResults.add(result);
            if (!dryRun) recordRun(tenantId, key, Times.format(now), result.toString());
        }
        response.put("recordPolicies", recordResults);
        if (!dryRun) {
            response.put("requestsCreated", created);
            sink.record(AuditRecord.of(tenantId, "RETENTION_RUN")
                    .actor(
                            actor == Actor.SYSTEM ? AuditRecord.ActorType.SYSTEM : AuditRecord.ActorType.USER,
                            actor.id(),
                            actor.name())
                    .resource("RetentionRun", tenantId)
                    .detail("requestsCreated", created)
                    .detail("protectedByHold", protectedTotal)
                    .detail("skippedInProgress", skippedTotal)
                    .detail("policies", results.size())
                    .detail("recordPolicies", recordResults)
                    .build());
        }
        return response;
    }

    private enum Confirmation {
        ERASABLE,
        IN_PROGRESS,
        HIRED,
        UNKNOWN
    }

    /** Re-checks the live stage of each application, since the projection can lag or miss an undone decision. */
    private Confirmation confirm(String tenantId, List<Map<String, Object>> docs) {
        for (var doc : docs) {
            try {
                var info = directory.currentApplication(tenantId, Maps.text(doc, "applicationId"));
                if (info.isEmpty()) continue;
                var stage = info.get().stage();
                if ("HIRED".equals(stage)) return Confirmation.HIRED;
                if (stage != null && !Stages.CLOSED.contains(stage)) return Confirmation.IN_PROGRESS;
            } catch (DirectoryUnavailable unavailable) {
                return Confirmation.UNKNOWN;
            }
        }
        return Confirmation.ERASABLE;
    }

    /** Candidates with at least one application idle past the shortest policy period, with all their applications. */
    private Map<String, List<Map<String, Object>>> candidates(
            String tenantId, Instant now, List<Map<String, Object>> active) {
        int shortest = active.stream()
                .mapToInt(p -> ((Number) p.get("retentionMonths")).intValue())
                .min()
                .orElse(0);
        var idle = activity.idleSince(tenantId, Times.format(Times.minusMonths(now, shortest)), SCAN_LIMIT);
        var ids = new java.util.LinkedHashSet<String>();
        for (var doc : idle) {
            var candidate = Maps.text(doc, "candidateId");
            if (candidate == null) candidate = resolve(tenantId, doc);
            if (candidate != null) ids.add(candidate);
        }
        var out = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var id : ids) out.put(id, activity.byCandidate(tenantId, id));
        return out;
    }

    private String resolve(String tenantId, Map<String, Object> doc) {
        try {
            var info = directory.currentApplication(tenantId, Maps.text(doc, "applicationId"));
            if (info.isEmpty()
                    || info.get().candidateId() == null
                    || info.get().candidateId().isBlank()) return null;
            doc.put("candidateId", info.get().candidateId());
            activity.save(doc);
            return info.get().candidateId();
        } catch (DirectoryUnavailable unavailable) {
            return null;
        }
    }

    private void recordRun(String tenantId, String key, String at, String summary) {
        var override = policies.overrides(tenantId).stream()
                .filter(o -> key.equals(Maps.text(o, "key")))
                .findFirst()
                .orElseGet(() -> Maps.of("_id", tenantId + "|" + key, "tenantId", tenantId, "key", key));
        override.put("lastRunAt", at);
        override.put("lastRunSummary", summary);
        policies.save(override);
    }
}
