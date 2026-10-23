package com.openhire.talent.service;

import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.talent.domain.TalentTypes.Consent;
import com.openhire.talent.dto.TalentViews.ConsentView;
import com.openhire.talent.service.port.TalentPorts.ApplicationDirectory;
import com.openhire.talent.service.port.TalentPorts.ProfileRepository;
import org.springframework.stereotype.Service;

/** The candidate's own switch ("Consider me for future roles"), authorised by the application receipt. */
@Service
public class ConsentService {
    private final ApplicationDirectory applications;
    private final ProfileRepository profiles;
    private final ProfileProjector projector;
    private final AuditRecorder audit;

    public ConsentService(
            ApplicationDirectory applications,
            ProfileRepository profiles,
            ProfileProjector projector,
            AuditRecorder audit) {
        this.applications = applications;
        this.profiles = profiles;
        this.projector = projector;
        this.audit = audit;
    }

    public ConsentView current(String tenantId, String applicationId, String receipt) {
        var candidate = applications.verifyReceipt(tenantId, applicationId, receipt);
        return profiles.find(tenantId, candidate.candidateId())
                .map(profile -> new ConsentView(
                        profile.consent() == Consent.GRANTED,
                        profile.consentUpdatedAt() == null
                                ? null
                                : profile.consentUpdatedAt().toString()))
                .orElse(new ConsentView(false, null));
    }

    public ConsentView set(String tenantId, String applicationId, String receipt, boolean granted) {
        var candidate = applications.verifyReceipt(tenantId, applicationId, receipt);
        var profile = projector.applyConsent(
                tenantId, applicationId, candidate.candidateId(), candidate.candidateName(), granted);
        recordConsent(tenantId, profile.candidateId(), applicationId, granted);
        return new ConsentView(
                profile.consent() == Consent.GRANTED,
                profile.consentUpdatedAt() == null
                        ? null
                        : profile.consentUpdatedAt().toString());
    }

    /** The talent-specific action plus the generic consent record that the consent ledger is built from. */
    private void recordConsent(String tenantId, String candidateId, String applicationId, boolean granted) {
        var suffix = granted ? "GRANTED" : "REVOKED";
        for (var action : new String[] {"TALENT_CONSENT_" + suffix, "CONSENT_" + suffix})
            audit.record(AuditRecord.of(tenantId, action)
                    .candidate(candidateId)
                    .resource("TalentProfile", candidateId)
                    .about(candidateId, applicationId)
                    .detail("purpose", "TALENT_POOL")
                    .build());
    }
}
