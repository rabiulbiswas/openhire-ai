package com.openhire.talent.domain;

import com.openhire.talent.domain.TalentTypes.Scored;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Re-rank of the cosine shortlist: 0.55 semantic + 0.25 rule coverage + 0.10 recency + 0.10 history. The history
 * signal comes from stage movement only (never from past scores), so a historical assessment cannot lift a
 * candidate for a different job.
 */
public final class Ranking {
    public static final double SEMANTIC = 0.55;
    public static final double RULES = 0.25;
    public static final double RECENCY = 0.10;
    public static final double HISTORY = 0.10;
    private static final double HALF_LIFE_DAYS = 365.0;

    private Ranking() {}

    public static double score(double semantic, double ruleCoverage, double recency, double history) {
        return SEMANTIC * clamp(semantic)
                + RULES * clamp(ruleCoverage)
                + RECENCY * clamp(recency)
                + HISTORY * clamp(history);
    }

    public static Scored rank(TalentProfile profile, double semantic, List<String> requiredSkills, Instant now) {
        double rules = ruleCoverage(requiredSkills, profile.skills());
        double recency = recency(profile.lastActivityAt(), now);
        double history = history(profile);
        return new Scored(
                profile.candidateId(),
                score(semantic, rules, recency, history),
                clamp(semantic),
                rules,
                recency,
                history);
    }

    /** Share of the required skills the profile has; a job that lists none is neutral rather than a zero. */
    public static double ruleCoverage(List<String> required, List<String> skills) {
        var wanted = SkillTaxonomy.canonical(required);
        if (wanted.isEmpty()) return 0.5;
        var have = SkillTaxonomy.canonical(skills);
        return (double) wanted.stream().filter(have::contains).count() / wanted.size();
    }

    /** 1 for activity today, halving every year. */
    public static double recency(Instant lastActivity, Instant now) {
        if (lastActivity == null) return 0;
        double days = Math.max(0, Duration.between(lastActivity, now).toHours() / 24.0);
        return Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    /** Silver medalist 1.0, otherwise shortlisted at some point 0.5, otherwise 0. */
    public static double history(TalentProfile profile) {
        if (profile.silverMedalist()) return 1.0;
        return profile.everShortlisted() && !profile.hired() ? 0.5 : 0.0;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
