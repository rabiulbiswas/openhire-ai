package com.openhire.compliance.config;

import com.openhire.compliance.application.AuditLedger;
import com.openhire.compliance.application.ComplianceSettings;
import com.openhire.compliance.application.ConsentService;
import com.openhire.compliance.application.DecisionHistory;
import com.openhire.compliance.application.FairnessService;
import com.openhire.compliance.application.HoldPolicy;
import com.openhire.compliance.application.LegalHolds;
import com.openhire.compliance.application.OverviewService;
import com.openhire.compliance.application.PrivacyService;
import com.openhire.compliance.application.Projections;
import com.openhire.compliance.application.RetentionService;
import com.openhire.compliance.application.SelfIdentificationService;
import com.openhire.compliance.application.port.CompliancePorts.*;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ComplianceConfiguration {
    @Bean
    ComplianceSettings complianceSettings(
            @Value("${openhire.compliance.participants:}") String participants,
            @Value("${openhire.compliance.min-group-size:5}") int minGroupSize,
            @Value("${openhire.compliance.adverse-impact-threshold:0.8}") double threshold,
            @Value("${openhire.compliance.erasure-sla-days:30}") int slaDays,
            @Value("${openhire.compliance.stuck-after-hours:24}") int stuckHours,
            @Value("${openhire.compliance.export-ttl-days:7}") int exportTtlDays,
            @Value("${openhire.compliance.retention-batch:50}") int retentionBatch) {
        return new ComplianceSettings(
                Arrays.stream(participants.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList(),
                minGroupSize,
                threshold,
                slaDays,
                stuckHours,
                exportTtlDays,
                retentionBatch);
    }

    @Bean
    AuditLedger auditLedger(AuditStore store, AuditSink sink, Clock clock) {
        return new AuditLedger(store, sink, clock);
    }

    @Bean
    Projections projections(
            ActivityStore activity,
            DecisionStore decisions,
            Erased erased,
            ApplicationDirectory directory,
            Clock clock) {
        return new Projections(activity, decisions, erased, directory, clock);
    }

    @Bean
    DecisionHistory decisionHistory(
            DecisionStore decisions,
            ActivityStore activity,
            AuditStore audit,
            PrivacyStore privacy,
            ApplicationDirectory directory) {
        return new DecisionHistory(decisions, activity, audit, privacy, directory);
    }

    @Bean
    ConsentService consentService(ConsentStore store, AuditSink sink, AuditStore audit, Clock clock) {
        return new ConsentService(store, sink, audit, clock);
    }

    @Bean
    HoldPolicy holdPolicy(HoldStore holds, ActivityStore activity, Clock clock) {
        return new HoldPolicy(holds, activity, clock);
    }

    @Bean
    PrivacyService privacyService(
            PrivacyStore store,
            HoldPolicy holds,
            ActivityStore activity,
            DecisionStore decisions,
            SelfIdentificationStore selfIdentification,
            Cipher cipher,
            Erased erased,
            Events events,
            AuditSink sink,
            ApplicationDirectory directory,
            ComplianceSettings settings,
            Clock clock) {
        return new PrivacyService(
                store,
                holds,
                activity,
                decisions,
                selfIdentification,
                cipher,
                erased,
                events,
                sink,
                directory,
                settings,
                clock);
    }

    @Bean
    LegalHolds legalHolds(HoldStore store, AuditSink sink, Events events, Clock clock, PrivacyService privacy) {
        return new LegalHolds(store, sink, events, clock, privacy::resumeBlocked);
    }

    @Bean
    RetentionService retentionService(
            PolicyStore policies,
            ActivityStore activity,
            HoldPolicy holds,
            PrivacyService privacy,
            ApplicationDirectory directory,
            AuditSink sink,
            ComplianceSettings settings,
            Clock clock,
            RecordRetention records,
            HoldStore legalHolds) {
        return new RetentionService(
                policies, activity, holds, privacy, directory, sink, settings, clock, records, legalHolds);
    }

    @Bean
    SelfIdentificationService selfIdentificationService(
            SelfIdentificationStore store, Cipher cipher, ApplicationDirectory directory, Clock clock) {
        return new SelfIdentificationService(store, cipher, directory, clock);
    }

    @Bean
    FairnessService fairnessService(
            ActivityStore activity,
            SelfIdentificationStore selfIds,
            SelfIdentificationService selfIdentification,
            FairnessStore snapshots,
            Events events,
            ComplianceSettings settings,
            Clock clock) {
        return new FairnessService(activity, selfIds, selfIdentification, snapshots, events, settings, clock);
    }

    @Bean
    OverviewService overviewService(
            AuditStore audit,
            AuditLedger ledger,
            PrivacyService privacy,
            LegalHolds holds,
            FairnessService fairness,
            ConsentService consent,
            RetentionService retention,
            Clock clock) {
        return new OverviewService(audit, ledger, privacy, holds, fairness, consent, retention, clock);
    }
}
