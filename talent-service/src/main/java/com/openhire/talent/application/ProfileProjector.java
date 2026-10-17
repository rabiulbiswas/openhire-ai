package com.openhire.talent.application;

import com.openhire.talent.application.port.TalentPorts.AiGateway;
import com.openhire.talent.application.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.application.port.TalentPorts.ApplicationDirectory;
import com.openhire.talent.application.port.TalentPorts.ApplicationInfo;
import com.openhire.talent.application.port.TalentPorts.ConcurrentUpdate;
import com.openhire.talent.application.port.TalentPorts.EventOutbox;
import com.openhire.talent.application.port.TalentPorts.ProfileRepository;
import com.openhire.talent.application.port.TalentPorts.ResumeDirectory;
import com.openhire.talent.application.port.TalentPorts.RunRepository;
import com.openhire.talent.application.port.TalentPorts.VectorStore;
import com.openhire.talent.domain.Places;
import com.openhire.talent.domain.SkillTaxonomy;
import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.ApplicationRef;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.domain.TalentTypes.Consent;
import com.openhire.talent.domain.TalentTypes.Evidence;
import com.openhire.talent.domain.TalentTypes.StageStep;
import com.openhire.talent.domain.TalentTypes.StructuredResume;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds and maintains the talent projection from upstream events. Identity, applications and stage history are kept
 * for every candidate; resume-derived content and the vector exist only while consent is GRANTED.
 */
@Service
public class ProfileProjector {
    private static final Logger LOG = LoggerFactory.getLogger(ProfileProjector.class);
    private static final int ATTEMPTS = 4;
    private static final int RESUME_LIMIT = 8000;
    private static final int SUMMARY_EXCERPT = 1500;
    private static final int CHUNK_SIZE = 600;
    private static final int MAX_CHUNKS = 8;
    private static final int MAX_SKILLS = 40;
    private static final Pattern YEARS =
            Pattern.compile("(\\d{1,2})(?:\\.\\d)?\\s*\\+?\\s*(?:years|yrs)", Pattern.CASE_INSENSITIVE);

    private final ProfileRepository profiles;
    private final VectorStore vectors;
    private final RunRepository runs;
    private final ApplicationDirectory applications;
    private final ResumeDirectory resumes;
    private final AiGateway gateway;
    private final EventOutbox outbox;
    private final TalentSettings settings;
    private final Clock clock;

    public ProfileProjector(
            ProfileRepository profiles,
            VectorStore vectors,
            RunRepository runs,
            ApplicationDirectory applications,
            ResumeDirectory resumes,
            AiGateway gateway,
            EventOutbox outbox,
            TalentSettings settings,
            Clock clock) {
        this.profiles = profiles;
        this.vectors = vectors;
        this.runs = runs;
        this.applications = applications;
        this.resumes = resumes;
        this.gateway = gateway;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    // ---- Events -------------------------------------------------------------------------------------

    /** ApplicationSubmitted, ApplicationStageChanged and ApplicationScreened all refresh the same projection. */
    public void onApplicationEvent(
            String tenantId, String eventId, String type, String applicationId, Instant at, Map<String, ?> payload) {
        var info = applications.application(tenantId, applicationId);
        if (blank(info.candidateId())) {
            LOG.debug("Application {} has no candidate yet; projection skipped", applicationId);
            return;
        }
        var saved = update(tenantId, info, at, true, profile -> {
            if ("ApplicationStageChanged".equals(type) && payload != null)
                profile.recordStep(new StageStep(
                        eventId,
                        applicationId,
                        info.jobId(),
                        String.valueOf(payload.get("from")),
                        String.valueOf(payload.get("to")),
                        at));
        });
        ensureContent(saved, info);
    }

    /** A COMPLETE assessment (PARTIAL ones are ignored) adds its score to the application and, for consented profiles, its evidence. */
    public void onAssessment(
            String tenantId, String applicationId, String status, Integer score, List<String> notes, Instant at) {
        var info = applications.application(tenantId, applicationId);
        if (blank(info.candidateId())) return;
        var complete = "COMPLETE".equals(status);
        if (!complete) return;
        update(tenantId, info, at, true, profile -> {
            var kept = profile.consent() == Consent.GRANTED
                    ? notes.stream()
                            .map(note -> new Evidence(applicationId, info.jobTitle(), note))
                            .toList()
                    : List.<Evidence>of();
            profile.recordAssessment(applicationId, score, kept);
        });
    }

    /** Hired candidates become employees and leave the pool (unless internal mobility is on). */
    public void onEmployeeCreated(String tenantId, String candidateId, String applicationId) {
        var id = candidateId;
        if (blank(id) && !blank(applicationId))
            id = applications.application(tenantId, applicationId).candidateId();
        if (blank(id)) return;
        final var target = id;
        mutate(
                tenantId,
                target,
                () -> TalentProfile.create(tenantId, target, "", clock.instant(), settings.retentionMonths()),
                TalentProfile::markEmployee);
    }

    /** The survivor takes over the source's applications, history and (the stricter) consent. */
    public void onCandidateMerged(String tenantId, String sourceId, String survivorId) {
        if (sourceId.equals(survivorId)) return;
        var source = profiles.find(tenantId, sourceId);
        if (source.isEmpty()) return;
        var absorbed = source.get();
        mutate(
                tenantId,
                survivorId,
                () -> TalentProfile.create(
                        tenantId, survivorId, absorbed.name(), clock.instant(), settings.retentionMonths()),
                survivor -> survivor.absorb(absorbed, settings.retentionMonths()));
        profiles.delete(tenantId, sourceId);
        vectors.delete(tenantId, sourceId);
        vectors.delete(tenantId, survivorId);
        runs.removeCandidate(tenantId, sourceId);
        profiles.find(tenantId, survivorId).ifPresent(this::rebuild);
    }

    // ---- Consent ------------------------------------------------------------------------------------

    /** Applies the candidate's choice. Revocation removes the vector and all derived content at once. */
    public TalentProfile applyConsent(
            String tenantId, String applicationId, String candidateId, String name, boolean granted) {
        ApplicationInfo info = null;
        try {
            info = applications.application(tenantId, applicationId);
        } catch (RuntimeException unavailable) {
            LOG.warn("Application {} not readable while applying consent: {}", applicationId, unavailable.toString());
        }
        final var known = info;
        var target = known != null && !blank(known.candidateId()) ? known.candidateId() : candidateId;
        if (erased(tenantId, target)) throw new IllegalStateException("The candidate's data was erased");
        var now = clock.instant();
        var saved = mutate(
                tenantId,
                target,
                () -> TalentProfile.create(tenantId, target, name, now, settings.retentionMonths()),
                profile -> {
                    if (known != null) {
                        profile.recordApplication(toRef(known));
                        profile.rename(known.candidateName());
                    }
                    if (granted && profile.consent() != Consent.GRANTED) profile.grant(now);
                    if (!granted && profile.consent() != Consent.REVOKED) profile.revoke(now);
                });
        if (granted) ensureContent(saved, known);
        else {
            vectors.delete(tenantId, target);
            runs.removeCandidate(tenantId, target);
        }
        return saved;
    }

    // ---- Content ------------------------------------------------------------------------------------

    private void ensureContent(TalentProfile profile, ApplicationInfo eventInfo) {
        if (profile == null || profile.consent() != Consent.GRANTED) return;
        var latestId = profile.latestApplicationId();
        if (latestId == null) return;
        var latest = eventInfo != null && latestId.equals(eventInfo.applicationId())
                ? eventInfo
                : applications.application(profile.tenantId(), latestId);
        if (blank(latest.resumeId())) return;
        if (profile.contentBuilt() && profile.embedded() && Objects.equals(profile.resumeId(), latest.resumeId()))
            return;
        buildContent(profile.tenantId(), profile.candidateId(), latest);
    }

    private void rebuild(TalentProfile profile) {
        if (profile.consent() != Consent.GRANTED || profile.latestApplicationId() == null) return;
        buildContent(
                profile.tenantId(),
                profile.candidateId(),
                applications.application(profile.tenantId(), profile.latestApplicationId()));
    }

    private void buildContent(String tenantId, String candidateId, ApplicationInfo latest) {
        if (blank(latest.resumeId())) return;
        var text = resumes.text(tenantId, latest.resumeId()).orElse(null);
        if (text == null || text.isBlank()) return;
        if (text.length() > RESUME_LIMIT) text = text.substring(0, RESUME_LIMIT);
        StructuredResume structured = null;
        try {
            structured = gateway.structureResume(tenantId, text);
        } catch (AiUnavailableException unavailable) {
            LOG.info("Resume structuring unavailable; using the keyword taxonomy ({})", unavailable.getMessage());
        }
        var skills = new LinkedHashSet<String>();
        if (structured != null) skills.addAll(SkillTaxonomy.canonical(structured.skills()));
        skills.addAll(SkillTaxonomy.extract(text));
        var skillList = skills.stream().limit(MAX_SKILLS).toList();
        var titles = structured == null
                ? List.<String>of()
                : structured.titles().stream().filter(t -> !blank(t)).distinct().toList();
        var locations = Places.extract(text).stream().limit(3).toList();
        var years = structured != null && structured.totalYearsExperience() != null
                ? structured.totalYearsExperience()
                : yearsIn(text);
        var headline = structured == null ? null : structured.headline();
        var summary = summary(headline, titles, skillList, years, locations, text);
        var chunks = chunks(text);
        var now = clock.instant();

        float[] vector = null;
        try {
            vector = gateway.embed(tenantId, summary);
        } catch (AiUnavailableException unavailable) {
            LOG.info("Embedding unavailable; the profile is queued for a retry ({})", unavailable.getMessage());
        }
        final var embedded = vector;
        var saved = mutate(tenantId, candidateId, () -> null, profile -> {
            if (profile.consent() != Consent.GRANTED) return;
            profile.applyContent(
                    latest.resumeId(), headline, skillList, titles, locations, years, summary, chunks, now);
            profile.markEmbedded(embedded != null);
        });
        if (saved == null) return;
        if (embedded != null) {
            vectors.save(tenantId, candidateId, embedded);
            // A revocation that landed between the save above and this write must not leave a vector behind.
            if (profiles.find(tenantId, candidateId)
                    .map(p -> p.consent() != Consent.GRANTED)
                    .orElse(true)) vectors.delete(tenantId, candidateId);
        }
    }

    /** Retries embeddings that failed while the model was down. Returns how many profiles were completed. */
    public int retryEmbeddings() {
        int done = 0;
        for (var profile : profiles.awaitingEmbedding(50)) {
            try {
                var vector = gateway.embed(profile.tenantId(), profile.summary());
                mutate(profile.tenantId(), profile.candidateId(), () -> null, p -> {
                    if (p.consent() == Consent.GRANTED && p.contentBuilt()) p.markEmbedded(true);
                });
                vectors.save(profile.tenantId(), profile.candidateId(), vector);
                if (profiles.find(profile.tenantId(), profile.candidateId())
                        .map(p -> p.consent() != Consent.GRANTED)
                        .orElse(true)) vectors.delete(profile.tenantId(), profile.candidateId());
                done++;
            } catch (AiUnavailableException unavailable) {
                return done;
            }
        }
        return done;
    }

    // ---- Retention ----------------------------------------------------------------------------------

    /** Marks profiles past their retention date expired and deletes their vectors and stored explanations. */
    public int sweepRetention() {
        int expired = 0;
        var now = clock.instant();
        for (var profile : profiles.due(now, 500)) {
            try {
                profile.expire();
                profiles.save(profile);
            } catch (ConcurrentUpdate busy) {
                continue;
            }
            vectors.delete(profile.tenantId(), profile.candidateId());
            runs.removeCandidate(profile.tenantId(), profile.candidateId());
            expired++;
        }
        outbox.purgeDelivered(now.minus(java.time.Duration.ofDays(1)));
        return expired;
    }

    // ---- Persistence helpers --------------------------------------------------------------------------

    private TalentProfile update(
            String tenantId, ApplicationInfo info, Instant at, boolean activity, Consumer<TalentProfile> change) {
        return mutate(
                tenantId,
                info.candidateId(),
                () -> TalentProfile.create(
                        tenantId, info.candidateId(), info.candidateName(), at, settings.retentionMonths()),
                profile -> {
                    profile.rename(info.candidateName());
                    profile.recordApplication(toRef(info));
                    if (activity) profile.recordActivity(at, settings.retentionMonths());
                    change.accept(profile);
                });
    }

    /** Read-modify-write with a retry when another writer got there first; a null factory means "only if present". */
    private TalentProfile mutate(
            String tenantId,
            String candidateId,
            java.util.function.Supplier<TalentProfile> factory,
            Consumer<TalentProfile> change) {
        // A late event must not bring back a profile that was erased.
        if (erased(tenantId, candidateId)) return null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var profile = profiles.find(tenantId, candidateId).orElseGet(factory);
            if (profile == null) return null;
            change.accept(profile);
            try {
                return profiles.save(profile);
            } catch (ConcurrentUpdate conflict) {
                // Re-read and apply the change again.
            }
        }
        throw new IllegalStateException("Talent profile kept changing; the event will be retried");
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the projector can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    private boolean erased(String tenantId, String candidateId) {
        return tombstones != null && tombstones.isErased(tenantId, candidateId);
    }

    private static ApplicationRef toRef(ApplicationInfo info) {
        return new ApplicationRef(
                info.applicationId(), info.jobId(), info.jobTitle(), info.stage(), info.appliedAt(), null);
    }

    private static String summary(
            String headline,
            List<String> titles,
            List<String> skills,
            Double years,
            List<String> locations,
            String text) {
        var parts = new ArrayList<String>();
        if (!blank(headline)) parts.add(headline);
        if (!titles.isEmpty()) parts.add("Roles: " + String.join(", ", titles));
        if (!skills.isEmpty()) parts.add("Skills: " + String.join(", ", skills));
        if (years != null) parts.add(String.format(java.util.Locale.ROOT, "Experience: %.0f years", years));
        if (!locations.isEmpty()) parts.add("Locations: " + String.join(", ", locations));
        parts.add(text.length() > SUMMARY_EXCERPT ? text.substring(0, SUMMARY_EXCERPT) : text);
        return String.join("\n", parts);
    }

    static List<Chunk> chunks(String text) {
        var chunks = new ArrayList<Chunk>();
        var current = new StringBuilder();
        for (var paragraph : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
            var value = paragraph.strip();
            if (value.isEmpty()) continue;
            if (current.length() > 0 && current.length() + value.length() > CHUNK_SIZE) {
                chunks.add(
                        new Chunk("c" + (chunks.size() + 1), current.toString().strip()));
                current.setLength(0);
            }
            while (value.length() > CHUNK_SIZE) {
                chunks.add(new Chunk("c" + (chunks.size() + 1), value.substring(0, CHUNK_SIZE)));
                value = value.substring(CHUNK_SIZE);
            }
            current.append(value).append('\n');
            if (chunks.size() >= MAX_CHUNKS) break;
        }
        if (current.length() > 0 && chunks.size() < MAX_CHUNKS)
            chunks.add(new Chunk("c" + (chunks.size() + 1), current.toString().strip()));
        return chunks;
    }

    static Double yearsIn(String text) {
        double best = -1;
        var matcher = YEARS.matcher(text);
        while (matcher.find()) best = Math.max(best, Double.parseDouble(matcher.group(1)));
        return best >= 0 && best <= 45 ? best : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
