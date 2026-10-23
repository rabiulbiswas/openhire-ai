package com.openhire.compliance.service;

import com.openhire.compliance.service.InMemory.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A whole compliance-service application layer wired to in-memory ports. */
public final class Rig {
    public static final String TENANT = "t1";

    public final MutableClock clock = new MutableClock();
    public final Audit audit = new Audit();
    public final Activity activity = new Activity();
    public final Decisions decisions = new Decisions();
    public final Consent consentStore = new Consent();
    public final Privacy privacyStore = new Privacy();
    public final Holds holdStore = new Holds();
    public final Policies policyStore = new Policies();
    public final SelfIds selfIds = new SelfIds();
    public final Snapshots snapshots = new Snapshots();
    public final EventLog events = new EventLog();
    public final Sink sink = new Sink();
    public final ErasedSet erased = new ErasedSet();
    public final Directory directory = new Directory();
    public final ComplianceSettings settings;

    public final AuditLedger ledger;
    public final Projections projections;
    public final DecisionHistory history;
    public final ConsentService consent;
    public final HoldPolicy holdPolicy;
    public final PrivacyService privacy;
    public final LegalHolds holds;
    public final RetentionService retention;
    public final SelfIdentificationService selfIdentification;
    public final FairnessService fairness;
    public final OverviewService overview;

    public Rig() {
        this(List.of("application-service", "talent-service", "employee-service"));
    }

    Rig(List<String> participants) {
        settings = ComplianceSettings.defaults(participants);
        ledger = new AuditLedger(audit, sink, clock);
        projections = new Projections(activity, decisions, erased, directory, clock);
        history = new DecisionHistory(decisions, activity, audit, privacyStore, directory);
        consent = new ConsentService(consentStore, sink, audit, clock);
        holdPolicy = new HoldPolicy(holdStore, activity, clock);
        privacy = new PrivacyService(
                privacyStore,
                holdPolicy,
                activity,
                decisions,
                selfIds,
                new XorCipher(),
                erased,
                events,
                sink,
                directory,
                settings,
                clock);
        holds = new LegalHolds(holdStore, sink, events, clock, privacy::resumeBlocked);
        retention = new RetentionService(
                policyStore,
                activity,
                holdPolicy,
                privacy,
                directory,
                sink,
                settings,
                clock,
                new com.openhire.compliance.service.port.CompliancePorts.RecordRetention() {
                    public List<String> tenants() {
                        return List.of();
                    }

                    public Map<String, Object> run(
                            String tenant, String policy, String cutoff, boolean dryRun, int limit) {
                        return Maps.of(
                                "key", policy, "cutoff", cutoff, "dryRun", dryRun, "processed", 0, "limit", limit);
                    }
                },
                holdStore);
        selfIdentification = new SelfIdentificationService(selfIds, new XorCipher(), directory, clock);
        fairness = new FairnessService(activity, selfIds, selfIdentification, snapshots, events, settings, clock);
        overview = new OverviewService(audit, ledger, privacy, holds, fairness, consent, retention, clock);
    }

    public static Map<String, Object> envelope(
            String type, String tenant, String aggregateId, String at, Map<String, Object> payload) {
        return Maps.of(
                "eventId", UUID.randomUUID().toString(),
                "producer", "phase3-workflow",
                "eventType", type,
                "eventVersion", 1,
                "occurredAt", at,
                "tenantId", tenant,
                "aggregateType", "Application",
                "aggregateId", aggregateId,
                "payload", payload);
    }

    public static Map<String, Object> auditEvent(
            String tenant, String action, String applicationId, String candidateId, String at) {
        return Maps.of(
                "eventId",
                UUID.randomUUID().toString(),
                "producer",
                "offer-service",
                "eventType",
                AuditLedger.EVENT_TYPE,
                "occurredAt",
                at,
                "tenantId",
                tenant,
                "payload",
                Maps.of(
                        "action", action,
                        "actor", Maps.of("type", "USER", "id", "u1", "name", "Asha"),
                        "resource", Maps.of("type", "Offer", "id", "off_1"),
                        "candidateId", candidateId,
                        "applicationId", applicationId,
                        "details", Maps.of("versionNo", 2, "reasonCode", "OTHER"),
                        "at", at));
    }

    /** Feeds a submitted application and returns nothing; the activity document is then in the store. */
    public void submitted(String applicationId, String jobId, String at) {
        projections.accept(envelope(
                "ApplicationSubmitted", TENANT, applicationId, at, Maps.of("jobId", jobId, "source", "CAREER")));
    }

    public void stage(String applicationId, String from, String to, String actor, String at) {
        projections.accept(envelope(
                "ApplicationStageChanged",
                TENANT,
                applicationId,
                at,
                Maps.of("from", from, "to", to, "actorId", actor)));
    }
}
