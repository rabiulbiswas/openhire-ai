package com.openhire.talent.application;

import com.openhire.talent.application.TalentViews.CandidateView;
import com.openhire.talent.application.TalentViews.SearchResult;
import com.openhire.talent.application.port.TalentPorts.AiGateway;
import com.openhire.talent.application.port.TalentPorts.AiUnavailableException;
import com.openhire.talent.application.port.TalentPorts.EventOutbox;
import com.openhire.talent.application.port.TalentPorts.ExplainRequest;
import com.openhire.talent.application.port.TalentPorts.InvitationRepository;
import com.openhire.talent.application.port.TalentPorts.JobDirectory;
import com.openhire.talent.application.port.TalentPorts.JobInfo;
import com.openhire.talent.application.port.TalentPorts.ProfileRepository;
import com.openhire.talent.application.port.TalentPorts.RunRepository;
import com.openhire.talent.application.port.TalentPorts.VectorStore;
import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.Narratives;
import com.openhire.talent.domain.Ranking;
import com.openhire.talent.domain.RediscoveryRun;
import com.openhire.talent.domain.RediscoveryRun.Match;
import com.openhire.talent.domain.SkillTaxonomy;
import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.domain.TalentTypes.Scored;
import com.openhire.talent.domain.Vectors;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds past candidates for a job: filter, exact cosine over the tenant's vectors, re-rank, then explain the top few.
 * Historical and current evidence reach the explanation model as separate lists; only current evidence (the profile
 * as it is now) and stage movement feed the score.
 */
@Service
public class RediscoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(RediscoveryService.class);
    private static final int SUMMARY_LIMIT = 800;
    private static final int CURRENT_CHUNKS = 3;

    private final ProfileRepository profiles;
    private final VectorStore vectors;
    private final RunRepository runs;
    private final InvitationRepository invitations;
    private final AiGateway gateway;
    private final JobDirectory jobs;
    private final EventOutbox outbox;
    private final TalentSettings settings;
    private final Clock clock;

    public RediscoveryService(
            ProfileRepository profiles,
            VectorStore vectors,
            RunRepository runs,
            InvitationRepository invitations,
            AiGateway gateway,
            JobDirectory jobs,
            EventOutbox outbox,
            TalentSettings settings,
            Clock clock) {
        this.profiles = profiles;
        this.vectors = vectors;
        this.runs = runs;
        this.invitations = invitations;
        this.gateway = gateway;
        this.jobs = jobs;
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clock;
    }

    /** job.published.v1: one run per event, so a redelivery does nothing. */
    public void onJobPublished(String tenantId, String jobId, String eventId) {
        if (runs.existsByTrigger(tenantId, eventId)) return;
        run(tenantId, jobId, "JOB_PUBLISHED", eventId);
    }

    /** Without a job id, the job of the most recent run (the most recently published job). */
    public SearchResult forJob(String tenantId, String jobId, boolean refresh) {
        var target = jobId;
        if (target == null || target.isBlank())
            target = runs.latestForTenant(tenantId).map(RediscoveryRun::jobId).orElse(null);
        if (target == null) return new SearchResult(List.of(), Filters.none(), null, List.of(), null, null, null);
        var cached = runs.latest(tenantId, target);
        var now = clock.instant();
        if (!refresh
                && cached.isPresent()
                && cached.get().createdAt().plus(settings.runCache()).isAfter(now)) return view(tenantId, cached.get());
        return view(tenantId, run(tenantId, target, "ON_DEMAND", null));
    }

    RediscoveryRun run(String tenantId, String jobId, String trigger, String triggerEventId) {
        var job = jobs.job(tenantId, jobId);
        var now = clock.instant();
        var cooling = coolingDown(tenantId);
        var pool = profiles.searchable(tenantId, now, settings.internalMobility()).stream()
                .filter(profile -> !profile.appliedTo(jobId) && !cooling.contains(profile.candidateId()))
                .toList();

        var queryText = queryText(job);
        float[] query = null;
        try {
            query = gateway.embed(tenantId, queryText);
        } catch (AiUnavailableException unavailable) {
            LOG.info("Query embedding unavailable for job {}; ranking lexically ({})", jobId, unavailable.getMessage());
        }
        var stored = query == null ? Map.<String, float[]>of() : vectors.all(tenantId);
        var shortlist = new ArrayList<Semantic>();
        for (var profile : pool) {
            double semantic;
            if (query != null) {
                var vector = stored.get(profile.candidateId());
                if (vector == null) continue;
                semantic = Vectors.cosine(query, vector);
            } else semantic = Vectors.lexical(queryText, profile.summary());
            shortlist.add(new Semantic(profile, semantic));
        }
        shortlist.sort(Comparator.comparingDouble(Semantic::semantic).reversed());
        var top = shortlist.stream().limit(settings.rediscoveryTop()).toList();

        var ranked = top.stream()
                .map(entry -> Map.entry(
                        entry.profile(), Ranking.rank(entry.profile(), entry.semantic(), job.mustHaveSkills(), now)))
                .sorted(Comparator.comparingDouble((Map.Entry<TalentProfile, Scored> entry) ->
                                entry.getValue().score())
                        .reversed())
                .limit(settings.resultLimit())
                .toList();

        var matches = new ArrayList<Match>();
        boolean modelUp = true;
        int index = 0;
        for (var entry : ranked) {
            var profile = entry.getKey();
            var scored = entry.getValue();
            String why = null;
            String historical = null;
            boolean explained = false;
            if (modelUp && index < settings.explainTop()) {
                try {
                    var explanation = gateway.explainFit(tenantId, explainRequest(job, profile, queryText));
                    why = explanation.whyFitNow();
                    historical = explanation.historicalEvidence();
                    explained = !blank(why);
                } catch (AiUnavailableException unavailable) {
                    modelUp = false;
                    LOG.info(
                            "Explanations unavailable for job {}; using templates ({})",
                            jobId,
                            unavailable.getMessage());
                }
            }
            if (!explained) why = Narratives.whyFitNow(profile, job.mustHaveSkills());
            if (blank(historical)) historical = Narratives.historicalEvidence(profile);
            matches.add(new Match(
                    profile.candidateId(),
                    profile.name(),
                    profile.displayTitle(),
                    (int) Math.round(100 * scored.score()),
                    scored.semantic(),
                    scored.ruleCoverage(),
                    scored.recency(),
                    scored.history(),
                    why,
                    historical,
                    explained));
            index++;
        }
        var run = runs.save(new RediscoveryRun(
                UUID.randomUUID().toString(),
                tenantId,
                jobId,
                job.title(),
                trigger,
                triggerEventId,
                now,
                query == null ? "LEXICAL" : "EMBEDDING",
                chips(job),
                matches));
        outbox.publish(
                tenantId,
                "talent.rediscovery.completed.v1",
                "RediscoveryRun",
                run.id(),
                Map.of("jobId", jobId, "matches", matches.size()));
        return run;
    }

    private SearchResult view(String tenantId, RediscoveryRun run) {
        var cooling = coolingDown(tenantId);
        var candidates = run.matches().stream()
                .filter(match -> !cooling.contains(match.candidateId()))
                .map(match -> new CandidateView(
                        match.candidateId(),
                        match.name(),
                        match.title(),
                        match.matchScore(),
                        match.whyFitNow(),
                        match.historicalEvidence(),
                        match.explained()))
                .toList();
        return new SearchResult(
                run.chips() == null ? List.of() : run.chips(),
                new Filters(List.of(), List.of(), null, null, null, null),
                run.jobTitle(),
                candidates,
                run.jobTitle(),
                run.jobId(),
                run.semanticMode());
    }

    private Set<String> coolingDown(String tenantId) {
        return invitations.since(tenantId, clock.instant().minus(settings.cooldown())).stream()
                .map(Invitation::candidateId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private ExplainRequest explainRequest(JobInfo job, TalentProfile profile, String queryText) {
        var historical = new ArrayList<Chunk>();
        var lines = Narratives.historyLines(profile);
        for (int i = 0; i < lines.size(); i++) historical.add(new Chunk("history-" + (i + 1), lines.get(i)));
        var notes = Narratives.assessmentNotes(profile);
        for (int i = 0; i < notes.size(); i++) historical.add(new Chunk("assessment-" + (i + 1), notes.get(i)));
        var current = profile.chunks().stream()
                .sorted(Comparator.comparingDouble((Chunk chunk) -> Vectors.lexical(queryText, chunk.text()))
                        .reversed())
                .limit(CURRENT_CHUNKS)
                .toList();
        var summary = job.description() == null ? "" : job.description();
        if (summary.length() > SUMMARY_LIMIT) summary = summary.substring(0, SUMMARY_LIMIT);
        return new ExplainRequest(job.title(), summary, job.criteria(), historical, current);
    }

    /** Chips describing the job-derived query, in the same wording as search chips. */
    static List<String> chips(JobInfo job) {
        var chips = new ArrayList<String>();
        chips.add("Role: " + job.title());
        job.mustHaveSkills().forEach(skill -> chips.add("Skill: " + skill));
        return chips;
    }

    static String queryText(JobInfo job) {
        var parts = new ArrayList<String>();
        parts.add(job.title());
        if (!blank(job.description())) parts.add(job.description());
        if (!job.mustHaveSkills().isEmpty())
            parts.add("Skills: " + String.join(", ", SkillTaxonomy.canonical(job.mustHaveSkills())));
        if (!job.criteria().isEmpty()) parts.add("Criteria: " + String.join(", ", job.criteria()));
        var text = String.join("\n", parts);
        return text.length() > 8000 ? text.substring(0, 8000) : text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Semantic(TalentProfile profile, double semantic) {}
}
