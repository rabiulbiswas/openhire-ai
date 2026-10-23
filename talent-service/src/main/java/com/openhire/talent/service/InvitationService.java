package com.openhire.talent.service;

import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.talent.domain.Invitation;
import com.openhire.talent.service.port.TalentPorts.EventOutbox;
import com.openhire.talent.service.port.TalentPorts.InvitationRepository;
import com.openhire.talent.service.port.TalentPorts.JobDirectory;
import com.openhire.talent.service.port.TalentPorts.Messenger;
import com.openhire.talent.service.port.TalentPorts.ProfileRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A person's click that sends the re-engagement template. Never automatic. */
@Service
public class InvitationService {
    static final String TEMPLATE = "talent-reengagement";
    private static final Set<String> ALLOWED = Set.of("RECRUITER", "HR_ADMIN", "ORG_ADMIN", "SUPER_ADMIN");

    public record Viewer(String actorId, Set<String> roles) {
        public boolean mayInvite() {
            return roles.stream().anyMatch(ALLOWED::contains);
        }
    }

    public static final class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    private final ProfileRepository profiles;
    private final InvitationRepository invitations;
    private final JobDirectory jobs;
    private final Messenger messenger;
    private final EventOutbox outbox;
    private final TalentSettings settings;
    private final Clock clock;
    private final AuditRecorder audit;

    public InvitationService(
            ProfileRepository profiles,
            InvitationRepository invitations,
            JobDirectory jobs,
            Messenger messenger,
            EventOutbox outbox,
            TalentSettings settings,
            Clock clock,
            AuditRecorder audit) {
        this.profiles = profiles;
        this.invitations = invitations;
        this.jobs = jobs;
        this.messenger = messenger;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    public Invitation invite(String tenantId, Viewer viewer, String candidateId, String jobId) {
        if (!viewer.mayInvite()) throw new AccessDenied("Recruiter access is required to invite candidates");
        var now = clock.instant();
        var profile = profiles.find(tenantId, candidateId)
                .filter(value -> value.searchable(now, settings.internalMobility()))
                .orElseThrow(
                        () -> new ResourceNotFoundException("Candidate " + candidateId + " is not in the talent pool"));
        var last = invitations.latestFor(tenantId, candidateId);
        if (last.isPresent() && last.get().invitedAt().plus(settings.cooldown()).isAfter(now))
            throw new IllegalStateException("Candidate was already invited on "
                    + last.get().invitedAt() + "; wait " + settings.cooldown().toDays() + " days between invitations");
        if (profile.appliedTo(jobId)) throw new IllegalStateException("Candidate already applied to this job");

        String jobTitle = null;
        if (jobId != null && !jobId.isBlank())
            jobTitle = jobs.job(tenantId, jobId).title();
        var applicationId = profile.latestApplicationId();
        if (applicationId == null)
            throw new IllegalStateException("Candidate has no application to reach them through");
        var variables = new LinkedHashMap<String, String>();
        variables.put("job.title", jobTitle == null ? "relevant" : jobTitle);
        var sent = messenger.sendTemplate(tenantId, applicationId, TEMPLATE, variables, viewer.actorId());
        if (!sent.sent())
            throw new IllegalStateException(
                    "The invitation was not sent (" + (sent.reason() == null ? "unknown" : sent.reason()) + ")");

        var invitation = invitations.save(new Invitation(
                UUID.randomUUID().toString(),
                tenantId,
                candidateId,
                jobId,
                jobTitle,
                applicationId,
                viewer.actorId(),
                now));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("invitationId", invitation.id());
        payload.put("candidateId", candidateId);
        if (jobId != null && !jobId.isBlank()) payload.put("jobId", jobId);
        payload.put("actorId", viewer.actorId());
        outbox.publish(tenantId, "talent.invited.v1", "Invitation", invitation.id(), payload);
        var record = AuditRecord.of(tenantId, "TALENT_INVITED")
                .user(viewer.actorId(), null)
                .resource("Invitation", invitation.id())
                .about(candidateId, applicationId);
        if (jobId != null && !jobId.isBlank()) record.detail("jobId", jobId);
        audit.record(record.build());
        return invitation;
    }
}
