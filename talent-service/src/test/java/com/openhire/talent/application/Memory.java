package com.openhire.talent.application;

import com.openhire.talent.AuditProbe;
import com.openhire.talent.application.port.TalentPorts.AiGateway;
import com.openhire.talent.application.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.application.port.TalentPorts.ApplicationDirectory;
import com.openhire.talent.application.port.TalentPorts.ApplicationInfo;
import com.openhire.talent.application.port.TalentPorts.EventOutbox;
import com.openhire.talent.application.port.TalentPorts.ExplainRequest;
import com.openhire.talent.application.port.TalentPorts.Explanation;
import com.openhire.talent.application.port.TalentPorts.InvitationRepository;
import com.openhire.talent.application.port.TalentPorts.JobDirectory;
import com.openhire.talent.application.port.TalentPorts.JobInfo;
import com.openhire.talent.application.port.TalentPorts.Messenger;
import com.openhire.talent.application.port.TalentPorts.NlFilter;
import com.openhire.talent.application.port.TalentPorts.ProfileRepository;
import com.openhire.talent.application.port.TalentPorts.ReceiptInfo;
import com.openhire.talent.application.port.TalentPorts.ReceiptRejected;
import com.openhire.talent.application.port.TalentPorts.ResumeDirectory;
import com.openhire.talent.application.port.TalentPorts.RunRepository;
import com.openhire.talent.application.port.TalentPorts.TemplateSend;
import com.openhire.talent.application.port.TalentPorts.VectorStore;
import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.RediscoveryRun;
import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.domain.TalentTypes.StructuredResume;
import com.openhire.talent.domain.Vectors;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory fakes of every port, sharing state so tests can inspect what was stored and sent. */
final class Memory {
    static final String TENANT = "t1";
    static final Instant START = Instant.parse("2026-10-15T00:00:00Z");

    final MutableClock clock = new MutableClock(START);
    final Map<String, TalentProfile> profileRows = new LinkedHashMap<>();
    final Map<String, float[]> vectorRows = new HashMap<>();
    final List<RediscoveryRun> runRows = new ArrayList<>();
    final List<Invitation> invitationRows = new ArrayList<>();
    final List<Map<String, Object>> events = new ArrayList<>();
    final Map<String, ApplicationInfo> applicationRows = new HashMap<>();
    final Map<String, String> resumeText = new HashMap<>();
    final Map<String, JobInfo> jobRows = new HashMap<>();
    final List<String> removedFromRuns = new ArrayList<>();
    int jobFetches;

    final FakeGateway gateway = new FakeGateway();
    final FakeMessenger messenger = new FakeMessenger();

    final TalentSettings settings =
            new TalentSettings(24, 500, 2, 50, Duration.ofDays(30), false, Duration.ofDays(60), Duration.ofMinutes(10));

    final ProfileRepository profiles = new ProfileRepository() {
        public Optional<TalentProfile> find(String tenantId, String candidateId) {
            return Optional.ofNullable(profileRows.get(TalentProfile.idFor(tenantId, candidateId)));
        }

        public Optional<TalentProfile> findByApplication(String tenantId, String applicationId) {
            return profileRows.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId) && p.application(applicationId) != null)
                    .findFirst();
        }

        public TalentProfile save(TalentProfile profile) {
            profileRows.put(profile.id(), profile);
            return profile;
        }

        public void delete(String tenantId, String candidateId) {
            profileRows.remove(TalentProfile.idFor(tenantId, candidateId));
        }

        public List<TalentProfile> searchable(String tenantId, Instant now, boolean includeEmployees) {
            return profileRows.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId) && p.searchable(now, includeEmployees))
                    .toList();
        }

        public List<TalentProfile> due(Instant now, int limit) {
            return profileRows.values().stream()
                    .filter(p -> p.state() == com.openhire.talent.domain.TalentTypes.ProfileState.ACTIVE
                            && !p.retentionUntil().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        public List<TalentProfile> awaitingEmbedding(int limit) {
            return profileRows.values().stream()
                    .filter(p -> p.consent() == com.openhire.talent.domain.TalentTypes.Consent.GRANTED
                            && p.contentBuilt()
                            && !p.embedded())
                    .limit(limit)
                    .toList();
        }
    };

    final VectorStore vectors = new VectorStore() {
        public void save(String tenantId, String candidateId, float[] vector) {
            vectorRows.put(TalentProfile.idFor(tenantId, candidateId), vector);
        }

        public void delete(String tenantId, String candidateId) {
            vectorRows.remove(TalentProfile.idFor(tenantId, candidateId));
        }

        public Map<String, float[]> all(String tenantId) {
            var result = new HashMap<String, float[]>();
            var prefix = tenantId + ":";
            vectorRows.forEach((key, value) -> {
                if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
            });
            return result;
        }
    };

    final RunRepository runs = new RunRepository() {
        public RediscoveryRun save(RediscoveryRun run) {
            runRows.add(run);
            return run;
        }

        public Optional<RediscoveryRun> latest(String tenantId, String jobId) {
            return runRows.stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.jobId().equals(jobId))
                    .max(Comparator.comparing(RediscoveryRun::createdAt));
        }

        public Optional<RediscoveryRun> latestForTenant(String tenantId) {
            return runRows.stream()
                    .filter(r -> r.tenantId().equals(tenantId))
                    .max(Comparator.comparing(RediscoveryRun::createdAt));
        }

        public boolean existsByTrigger(String tenantId, String triggerEventId) {
            return runRows.stream().anyMatch(r -> triggerEventId.equals(r.triggerEventId()));
        }

        public void removeCandidate(String tenantId, String candidateId) {
            removedFromRuns.add(candidateId);
            for (int i = 0; i < runRows.size(); i++) {
                var run = runRows.get(i);
                runRows.set(
                        i,
                        new RediscoveryRun(
                                run.id(),
                                run.tenantId(),
                                run.jobId(),
                                run.jobTitle(),
                                run.trigger(),
                                run.triggerEventId(),
                                run.createdAt(),
                                run.semanticMode(),
                                run.chips(),
                                run.matches().stream()
                                        .filter(m -> !m.candidateId().equals(candidateId))
                                        .toList()));
            }
        }
    };

    final InvitationRepository invitations = new InvitationRepository() {
        public Invitation save(Invitation invitation) {
            invitationRows.add(invitation);
            return invitation;
        }

        public Optional<Invitation> latestFor(String tenantId, String candidateId) {
            return invitationRows.stream()
                    .filter(i ->
                            i.tenantId().equals(tenantId) && i.candidateId().equals(candidateId))
                    .max(Comparator.comparing(Invitation::invitedAt));
        }

        public List<Invitation> since(String tenantId, Instant since) {
            return invitationRows.stream()
                    .filter(i -> i.tenantId().equals(tenantId) && !i.invitedAt().isBefore(since))
                    .toList();
        }
    };

    final ApplicationDirectory applications = new ApplicationDirectory() {
        public ApplicationInfo application(String tenantId, String applicationId) {
            var info = applicationRows.get(applicationId);
            if (info == null) throw new com.openhire.platform.web.ResourceNotFoundException("missing");
            return info;
        }

        public ReceiptInfo verifyReceipt(String tenantId, String applicationId, String receipt) {
            if (!"valid-receipt".equals(receipt)) throw new ReceiptRejected();
            var info = application(tenantId, applicationId);
            return new ReceiptInfo(info.candidateId(), info.candidateName());
        }
    };

    final ResumeDirectory resumes = (tenantId, resumeId) -> Optional.ofNullable(resumeText.get(resumeId));

    final JobDirectory jobs = (tenantId, jobId) -> {
        jobFetches++;
        var job = jobRows.get(jobId);
        if (job == null) throw new com.openhire.platform.web.ResourceNotFoundException("missing job");
        return job;
    };

    final EventOutbox outbox = new EventOutbox() {
        public void publish(
                String tenantId, String type, String aggregateType, String aggregateId, Map<String, Object> payload) {
            var event = new LinkedHashMap<String, Object>();
            event.put("type", type);
            event.put("aggregateId", aggregateId);
            event.put("payload", payload);
            events.add(event);
        }

        public void purgeDelivered(Instant before) {}
    };

    final AuditProbe audit = AuditProbe.working();

    ProfileProjector projector() {
        return new ProfileProjector(profiles, vectors, runs, applications, resumes, gateway, outbox, settings, clock);
    }

    RediscoveryService rediscovery() {
        return new RediscoveryService(profiles, vectors, runs, invitations, gateway, jobs, outbox, settings, clock);
    }

    SearchService search() {
        return new SearchService(profiles, vectors, invitations, gateway, settings, clock);
    }

    InvitationService invitationService() {
        return new InvitationService(profiles, invitations, jobs, messenger, outbox, settings, clock, audit.recorder());
    }

    ConsentService consent(ProfileProjector projector) {
        return new ConsentService(applications, profiles, projector, audit.recorder());
    }

    void application(
            String applicationId,
            String candidateId,
            String name,
            String jobId,
            String jobTitle,
            String stage,
            String resumeId) {
        applicationRows.put(
                applicationId,
                new ApplicationInfo(
                        applicationId, candidateId, name, jobId, jobTitle, stage, clock.instant(), resumeId));
    }

    /** A candidate who applied, has parsed resume text and opted in. */
    TalentProfile pooled(
            ProfileProjector projector, String candidateId, String name, String jobId, String jobTitle, String resume) {
        var application = "app-" + candidateId;
        application(application, candidateId, name, jobId, jobTitle, "REJECTED", "res-" + candidateId);
        resumeText.put("res-" + candidateId, resume);
        projector.onApplicationEvent(
                TENANT, "evt-sub-" + candidateId, "ApplicationSubmitted", application, clock.instant(), Map.of());
        return projector.applyConsent(TENANT, application, candidateId, name, true);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakeGateway implements AiGateway {
        boolean down;
        boolean embedDown;
        boolean explainDown;
        StructuredResume structured;
        NlFilter nl;
        final List<ExplainRequest> explainRequests = new ArrayList<>();
        final List<String> embedded = new ArrayList<>();

        /** A hashed bag of words: texts that share words are near each other, so rankings are meaningful. */
        public float[] embed(String tenantId, String text) {
            if (down || embedDown) throw new AiUnavailableException("embed unavailable", null);
            embedded.add(text);
            var vector = new float[64];
            for (var token : Vectors.tokens(text)) vector[Math.floorMod(token.hashCode(), 64)] += 1f;
            return vector;
        }

        public StructuredResume structureResume(String tenantId, String text) {
            if (down || structured == null) throw new AiUnavailableException("structure unavailable", null);
            return structured;
        }

        public NlFilter nlFilter(String tenantId, String query) {
            if (down || nl == null) throw new AiUnavailableException("nl unavailable", null);
            return nl;
        }

        public Explanation explainFit(String tenantId, ExplainRequest request) {
            if (down || explainDown) throw new AiUnavailableException("explain unavailable", null);
            explainRequests.add(request);
            return new Explanation(
                    "CURRENT: " + request.current().size() + " current excerpts reviewed",
                    "HISTORICAL: " + request.historical().size() + " past statements reviewed");
        }
    }

    static final class FakeMessenger implements Messenger {
        boolean optedOut;
        final List<Map<String, Object>> sent = new ArrayList<>();

        public TemplateSend sendTemplate(
                String tenantId,
                String applicationId,
                String templateKey,
                Map<String, String> variables,
                String actor) {
            if (optedOut) return new TemplateSend(false, "MARKETING_OPT_OUT");
            sent.add(Map.of(
                    "applicationId",
                    applicationId,
                    "templateKey",
                    templateKey,
                    "variables",
                    variables,
                    "actor",
                    actor));
            return new TemplateSend(true, null);
        }
    }

    static Filters filters(List<String> skills, List<String> locations) {
        return new Filters(locations, skills, null, null, null, null);
    }
}
