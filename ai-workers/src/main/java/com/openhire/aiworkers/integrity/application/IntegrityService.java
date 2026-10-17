package com.openhire.aiworkers.integrity.application;

import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.ApplicationDirectory;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.IdentityEntry;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.IdentityIndex;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.SignalStore;
import com.openhire.aiworkers.integrity.application.port.IntegrityPorts.Structuring;
import com.openhire.aiworkers.integrity.domain.DetectionContext;
import com.openhire.aiworkers.integrity.domain.Detector;
import com.openhire.aiworkers.integrity.domain.Identities;
import com.openhire.aiworkers.integrity.domain.Role;
import com.openhire.aiworkers.integrity.domain.Signal;
import com.openhire.aiworkers.matching.domain.Hashes;
import com.openhire.aiworkers.shared.application.Envelopes;
import com.openhire.aiworkers.shared.application.port.ResumeSource;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the advisory detectors for a processed resume. Signals never touch scores or the application's stage; each
 * detector failing on its own (for example the model being down for the timeline check) only skips that detector.
 */
public class IntegrityService {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrityService.class);

    private final List<Detector> detectors;
    private final ResumeSource resumes;
    private final ApplicationDirectory applications;
    private final Structuring structuring;
    private final IdentityIndex identities;
    private final SignalStore signals;
    private final Clock clock;
    private final AuditRecorder audit;

    public IntegrityService(
            List<Detector> detectors,
            ResumeSource resumes,
            ApplicationDirectory applications,
            Structuring structuring,
            IdentityIndex identities,
            SignalStore signals,
            Clock clock,
            AuditRecorder audit) {
        this.detectors = detectors;
        this.resumes = resumes;
        this.applications = applications;
        this.structuring = structuring;
        this.identities = identities;
        this.signals = signals;
        this.clock = clock;
        this.audit = audit;
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the service can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    public void analyze(String tenantId, String applicationId, String resumeId) {
        var application = applications.application(tenantId, applicationId);
        // A late resume event for an erased candidate must not raise signals or index the identity again.
        if (tombstones != null
                && !application.candidateId().isBlank()
                && tombstones.isErased(tenantId, application.candidateId())) return;
        if (!application.resumeId().isBlank() && !application.resumeId().equals(resumeId)) return;
        var resume = resumes.fetch(tenantId, resumeId);
        if (!resume.ready()) return;
        var candidateId = application.candidateId().isBlank() ? applicationId : application.candidateId();
        register(
                tenantId,
                applicationId,
                candidateId,
                application.candidateName(),
                application.candidateEmail(),
                application.candidatePhone());
        var roles = new java.util.concurrent.atomic.AtomicReference<List<Role>>();
        var context = new DetectionContext(
                tenantId,
                applicationId,
                candidateId,
                resumeId,
                application.candidateName(),
                application.candidateEmail(),
                application.candidatePhone(),
                resume.text(),
                () -> {
                    if (roles.get() == null) roles.set(structuring.roles(tenantId, resume.text()));
                    return roles.get();
                });
        for (var detector : detectors) {
            if (!detector.enabled()) continue;
            try {
                detector.detect(context)
                        .filter(f -> f.confidence() >= detector.threshold())
                        .ifPresent(finding -> {
                            var signal = new Signal(
                                    "sig_"
                                            + Hashes.sha256(tenantId, applicationId, resumeId, detector.type())
                                                    .substring(0, 24),
                                    tenantId,
                                    applicationId,
                                    candidateId,
                                    resumeId,
                                    detector.type(),
                                    finding.severity(),
                                    finding.confidence(),
                                    finding.evidence(),
                                    Signal.RAISED,
                                    clock.instant(),
                                    null,
                                    null,
                                    null);
                            signals.insertIfAbsent(signal, raised(signal));
                        });
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Detector {} skipped for application {} ({})",
                        detector.type(),
                        applicationId,
                        failure.getClass().getSimpleName());
            }
        }
    }

    public List<Signal> signals(String tenantId, String applicationId) {
        return signals.byApplication(tenantId, applicationId);
    }

    public Signal dismiss(String tenantId, String id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("reason is required (max 1000)");
        var signal = signals.find(tenantId, id).orElseThrow(() -> new ResourceNotFoundException("Signal not found"));
        if (Signal.DISMISSED.equals(signal.status())) return signal;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", signal.applicationId());
        payload.put("signalId", signal.id());
        payload.put("type", signal.type());
        var event = Envelopes.event("integrity.signal.dismissed.v1", tenantId, "IntegritySignal", signal.id(), payload);
        var dismissed = signals.dismiss(tenantId, id, actor, reason.trim(), event);
        // The free-text reason stays on the signal; the audit trail carries ids and the signal type only.
        dismissed.ifPresent(done -> audit.record(AuditRecord.of(tenantId, "INTEGRITY_SIGNAL_DISMISSED")
                .user(actor, null)
                .resource("IntegritySignal", done.id())
                .about(done.candidateId(), done.applicationId())
                .detail("signalType", done.type())
                .detail("severity", done.severity())
                .build()));
        return dismissed.orElse(signal);
    }

    private void register(
            String tenantId, String applicationId, String candidateId, String name, String email, String phone) {
        var names = Identities.nameTokens(tenantId, name);
        var values = new ArrayList<Map.Entry<String, String>>();
        values.add(Map.entry("EMAIL", Identities.email(tenantId, email)));
        values.add(Map.entry("PHONE", Identities.phone(tenantId, phone)));
        for (var value : values)
            if (!value.getValue().isEmpty())
                identities.register(new IdentityEntry(
                        tenantId, applicationId, candidateId, value.getKey(), value.getValue(), names));
    }

    private static Map<String, Object> raised(Signal signal) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", signal.applicationId());
        payload.put("candidateId", signal.candidateId());
        payload.put("signalId", signal.id());
        payload.put("type", signal.type());
        payload.put("severity", signal.severity());
        payload.put("confidence", signal.confidence());
        payload.put("evidence", signal.evidence());
        return Envelopes.event(
                "integrity.signal.raised.v1", signal.tenantId(), "IntegritySignal", signal.id(), payload);
    }
}
