package com.openhire.compliance.application;

import com.openhire.compliance.application.port.CompliancePorts.AuditFilter;
import com.openhire.compliance.application.port.CompliancePorts.AuditStore;
import com.openhire.compliance.domain.AuditEntry;
import com.openhire.compliance.domain.Times;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OverviewService {
    private static final Duration CHAIN_CACHE = Duration.ofMinutes(5);

    private record Checked(boolean valid, java.time.Instant at) {}

    private record Flags(int count, java.time.Instant at) {}

    private final AuditStore audit;
    private final AuditLedger ledger;
    private final PrivacyService privacy;
    private final LegalHolds holds;
    private final FairnessService fairness;
    private final ConsentService consent;
    private final RetentionService retention;
    private final Clock clock;
    private final Map<String, Checked> chain = new ConcurrentHashMap<>();
    private final Map<String, Flags> flags = new ConcurrentHashMap<>();

    public OverviewService(
            AuditStore audit,
            AuditLedger ledger,
            PrivacyService privacy,
            LegalHolds holds,
            FairnessService fairness,
            ConsentService consent,
            RetentionService retention,
            Clock clock) {
        this.audit = audit;
        this.ledger = ledger;
        this.privacy = privacy;
        this.holds = holds;
        this.fairness = fairness;
        this.consent = consent;
        this.retention = retention;
        this.clock = clock;
    }

    public Map<String, Object> overview(String tenantId) {
        var now = clock.instant();
        var since = Times.format(now.minus(Duration.ofDays(30)));
        var cached = chain.get(tenantId);
        if (cached == null || cached.at().plus(CHAIN_CACHE).isBefore(now)) {
            cached = new Checked(ledger.verify(tenantId).valid(), now);
            chain.put(tenantId, cached);
        }
        // Counting flags reads every application of the tenant, so the overview reuses a recent count.
        var cachedFlags = flags.get(tenantId);
        if (cachedFlags == null || cachedFlags.at().plus(CHAIN_CACHE).isBefore(now)) {
            cachedFlags = new Flags(fairness.flagCount(tenantId), now);
            flags.put(tenantId, cachedFlags);
        }
        List<Map<String, Object>> recent = audit.page(tenantId, AuditFilter.none(), null, 10).stream()
                .map(AuditEntry::view)
                .toList();
        var result = Maps.of(
                "auditRecords30d", audit.countSince(tenantId, since),
                "chainValid", cached.valid(),
                "openPrivacyRequests", privacy.open(tenantId),
                "stuckPrivacyRequests", privacy.stuck(tenantId),
                "activeLegalHolds", holds.activeCount(tenantId),
                "fairnessFlags", cachedFlags.count(),
                "activeConsentVersion", consent.activeVersion(tenantId),
                "lastRetentionRun", retention.lastRun(tenantId),
                "recentAudit", recent,
                "recentPrivacy", privacy.recent(tenantId, 5));
        result.putIfAbsent("lastRetentionRun", null);
        return result;
    }
}
