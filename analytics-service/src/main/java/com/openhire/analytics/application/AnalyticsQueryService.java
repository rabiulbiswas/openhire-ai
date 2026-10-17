package com.openhire.analytics.application;

import com.openhire.analytics.application.AnalyticsViews.AnalyticsKpis;
import com.openhire.analytics.application.AnalyticsViews.FunnelResult;
import com.openhire.analytics.application.AnalyticsViews.FunnelStage;
import com.openhire.analytics.application.AnalyticsViews.OverrideReason;
import com.openhire.analytics.application.AnalyticsViews.SourceQuality;
import com.openhire.analytics.application.AnalyticsViews.StageLatency;
import com.openhire.analytics.application.AnalyticsViews.TimeToHirePoint;
import com.openhire.analytics.application.port.AnalyticsPorts.AiDayFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.ApplicationFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.AssessmentFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.DecisionFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.JobTitles;
import com.openhire.analytics.application.port.AnalyticsPorts.OfferFacts;
import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.Facts.AiDayFact;
import com.openhire.analytics.domain.Facts.AssessmentFact;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.DecisionKind;
import com.openhire.analytics.domain.Facts.OfferFact;
import com.openhire.analytics.domain.Range;
import com.openhire.analytics.domain.Stats;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Tenant-scoped reads over the materialized views.
 *
 * <p>Definitions. A window is (now - range, now]; the previous period is the equally long window before it.
 * <ul>
 *   <li>Time to hire: application submitted to HIRED, in days, for hires made in the window; unhired applications
 *       are excluded. Reported as the median.
 *   <li>Offer acceptance: accepted / (accepted + declined + expired), each counted at the time it happened.
 *   <li>AI shortlist agreement: among applications where a person shortlisted or rejected at screening (latest
 *       decision, not undone) after a COMPLETE AI assessment, the share where the AI agreed. A score of at least
 *       {@value #SHORTLIST_SCORE} means the AI recommends a shortlist.
 *   <li>AI cost per screened applicant: AI cost (INR) of the window over distinct applications with a COMPLETE
 *       assessment in the window. AI requests are not tagged with an application, so this is tenant-wide even when
 *       a job is selected.
 *   <li>Funnel: applications submitted in the window, counted as reached at each stage. Reaching a stage implies
 *       having reached the ones before it (a hire counts as offered, interviewed, shortlisted, assessed).
 * </ul>
 */
public class AnalyticsQueryService {
    public static final int SHORTLIST_SCORE = 60;
    static final String CURRENCY = "INR";
    private static final List<String> FUNNEL =
            List.of("Applied", "AI assessed", "Shortlisted", "Interviewing", "Offer", "Hired");
    private static final List<String> STAGE_ORDER = List.of(
            "APPLIED", "NEEDS_ATTENTION", "AI_ASSESSED", "SHORTLISTED", "INTERVIEWING", "DECISION_PENDING", "OFFER");
    private static final List<String> REASON_ORDER =
            List.of("UNDERWEIGHTED_EVIDENCE", "OVERWEIGHTED_GAP", "CONTEXT_NOT_IN_RESUME", "OTHER");
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "CAREER", "Career site",
            "AGENCY", "Agency",
            "REFERRAL", "Referral",
            "INTERNAL", "Internal",
            "PUBLIC_API", "Public API",
            "RECRUITER", "Recruiter sourced");

    private final ApplicationFacts applications;
    private final AssessmentFacts assessments;
    private final DecisionFacts decisions;
    private final OfferFacts offers;
    private final AiDayFacts aiDays;
    private final JobTitles titles;
    private final Clock clock;

    public AnalyticsQueryService(
            ApplicationFacts applications,
            AssessmentFacts assessments,
            DecisionFacts decisions,
            OfferFacts offers,
            AiDayFacts aiDays,
            JobTitles titles,
            Clock clock) {
        this.applications = applications;
        this.assessments = assessments;
        this.decisions = decisions;
        this.offers = offers;
        this.aiDays = aiDays;
        this.titles = titles;
        this.clock = clock;
    }

    public AnalyticsKpis kpis(String tenant, String rangeKey, String jobId) {
        var range = Range.of(rangeKey, clock.instant());
        var previous = range.previous();
        var facts = applications.touchedSince(tenant, blankToNull(jobId), range.previousFrom());
        var offerFacts = offers.touchedSince(tenant, range.previousFrom()).stream()
                .filter(offer -> matches(jobId, offer.jobId()))
                .toList();
        var decisionFacts = decisions.between(tenant, range.previousFrom(), range.to()).stream()
                .filter(decision -> matches(jobId, decision.jobId()))
                .toList();

        var hire = timeToHire(facts, range);
        var hirePrevious = timeToHire(facts, previous);
        var acceptance = acceptance(offerFacts, range);
        var acceptancePrevious = acceptance(offerFacts, previous);
        var agreement = agreement(tenant, decisionFacts, range);
        var agreementPrevious = agreement(tenant, decisionFacts, previous);
        var cost = costPerScreened(tenant, range, false);
        var costPrevious = costPerScreened(tenant, previous, true);

        return new AnalyticsKpis(
                hire.isPresent() ? Stats.round(hire.getAsDouble(), 1) : 0,
                delta(hire, hirePrevious, 1, "day"),
                acceptance.isPresent() ? acceptance.getAsDouble() : 0,
                delta(acceptance, acceptancePrevious, 1, "pts"),
                agreement.isPresent() ? agreement.getAsDouble() : 0,
                delta(agreement, agreementPrevious, 1, "pts"),
                cost.isPresent() ? Stats.round(cost.getAsDouble(), 2) : 0,
                delta(cost, costPrevious, 2, CURRENCY),
                CURRENCY);
    }

    public FunnelResult funnel(String tenant, String jobId, String rangeKey) {
        var range = Range.of(rangeKey, clock.instant());
        var cohort = cohort(tenant, jobId, range);
        var counts = new long[FUNNEL.size()];
        for (var fact : cohort) for (int level = 0; level <= level(fact); level++) counts[level]++;
        var stages = new ArrayList<FunnelStage>();
        for (int i = 0; i < FUNNEL.size(); i++) stages.add(new FunnelStage(FUNNEL.get(i), counts[i]));
        return new FunnelResult(jobTitle(tenant, jobId, cohort), stages);
    }

    public List<TimeToHirePoint> timeToHire(String tenant, String rangeKey, String jobId) {
        var range = Range.of(rangeKey, clock.instant());
        var byWeek = new java.util.TreeMap<String, List<Double>>();
        for (var fact : applications.touchedSince(tenant, blankToNull(jobId), range.from())) {
            var days = hireDays(fact);
            if (days == null || !range.contains(fact.hiredAt())) continue;
            var at = fact.hiredAt().atZone(ZoneOffset.UTC);
            var week =
                    "%d-W%02d".formatted(at.get(IsoFields.WEEK_BASED_YEAR), at.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            byWeek.computeIfAbsent(week, key -> new ArrayList<>()).add(days);
        }
        return byWeek.entrySet().stream()
                .map(entry -> new TimeToHirePoint(
                        entry.getKey(),
                        Stats.round(Stats.median(entry.getValue()).orElse(0), 1)))
                .toList();
    }

    public List<SourceQuality> sources(String tenant, String rangeKey, String jobId) {
        var range = Range.of(rangeKey, clock.instant());
        var bySource = cohort(tenant, jobId, range).stream()
                .collect(Collectors.groupingBy(
                        fact -> fact.source() == null || fact.source().isBlank() ? "UNKNOWN" : fact.source()));
        return bySource.entrySet().stream()
                .map(entry -> {
                    long applicants = entry.getValue().size();
                    long shortlisted = entry.getValue().stream()
                            .filter(fact -> level(fact) >= 2)
                            .count();
                    long hires = entry.getValue().stream()
                            .filter(fact -> level(fact) >= 5)
                            .count();
                    return new SourceQuality(
                            SOURCE_LABELS.getOrDefault(entry.getKey(), label(entry.getKey())),
                            applicants,
                            Stats.pct(shortlisted, applicants),
                            hires);
                })
                .sorted(Comparator.comparingLong(SourceQuality::applicants)
                        .reversed()
                        .thenComparing(SourceQuality::source))
                .toList();
    }

    public List<OverrideReason> aiOverrides(String tenant, String rangeKey, String jobId) {
        var range = Range.of(rangeKey, clock.instant());
        var counts = new HashMap<String, Long>();
        decisions.between(tenant, range.from(), range.to()).stream()
                .filter(decision -> decision.kind() == DecisionKind.OVERRIDE && matches(jobId, decision.jobId()))
                .forEach(decision ->
                        counts.merge(decision.reasonCode() == null ? "OTHER" : decision.reasonCode(), 1L, Long::sum));
        return REASON_ORDER.stream()
                .filter(counts::containsKey)
                .map(reason -> new OverrideReason(reason, counts.get(reason)))
                .toList();
    }

    /** Hours spent in each stage, from entering it to entering the next one, for stages entered in the window. */
    public List<StageLatency> stageLatency(String tenant, String rangeKey, String jobId) {
        var range = Range.of(rangeKey, clock.instant());
        var hours = new LinkedHashMap<String, List<Double>>();
        for (var fact : applications.touchedSince(tenant, blankToNull(jobId), range.from())) {
            var timeline = fact.timeline();
            for (int i = 0; i + 1 < timeline.size(); i++) {
                var entry = timeline.get(i);
                if (ApplicationFact.TERMINAL.contains(entry.stage()) || !range.contains(entry.at())) continue;
                var spent = Duration.between(entry.at(), timeline.get(i + 1).at());
                hours.computeIfAbsent(entry.stage(), key -> new ArrayList<>()).add(spent.toSeconds() / 3600.0);
            }
        }
        return STAGE_ORDER.stream()
                .filter(hours::containsKey)
                .map(stage -> new StageLatency(
                        stage,
                        Stats.round(Stats.median(hours.get(stage)).orElse(0), 1),
                        Stats.round(Stats.percentile(hours.get(stage), 0.9).orElse(0), 1)))
                .toList();
    }

    private OptionalDouble timeToHire(List<ApplicationFact> facts, Range range) {
        return Stats.median(facts.stream()
                .filter(fact -> range.contains(fact.hiredAt()))
                .map(AnalyticsQueryService::hireDays)
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    private static Double hireDays(ApplicationFact fact) {
        if (fact.hiredAt() == null || fact.submittedAt() == null) return null;
        return Duration.between(fact.submittedAt(), fact.hiredAt()).toSeconds() / 86400.0;
    }

    private OptionalDouble acceptance(List<OfferFact> offerFacts, Range range) {
        long accepted =
                offerFacts.stream().filter(o -> range.contains(o.acceptedAt())).count();
        long declined =
                offerFacts.stream().filter(o -> range.contains(o.declinedAt())).count();
        long expired =
                offerFacts.stream().filter(o -> range.contains(o.expiredAt())).count();
        long answered = accepted + declined + expired;
        return answered == 0 ? OptionalDouble.empty() : OptionalDouble.of(Stats.pct(accepted, answered));
    }

    private OptionalDouble agreement(String tenant, List<DecisionFact> decisionFacts, Range range) {
        var latest = decisionFacts.stream()
                .filter(d -> d.screening() && !d.undone() && range.contains(d.at()))
                .collect(Collectors.toMap(
                        DecisionFact::applicationId, d -> d, (a, b) -> a.at().isAfter(b.at()) ? a : b));
        if (latest.isEmpty()) return OptionalDouble.empty();
        var byApplication = assessments.forApplications(tenant, latest.keySet()).stream()
                .filter(AssessmentFact::complete)
                .collect(Collectors.groupingBy(AssessmentFact::applicationId));
        long compared = 0;
        long agreed = 0;
        for (var decision : latest.values()) {
            var assessment = byApplication.getOrDefault(decision.applicationId(), List.of()).stream()
                    .filter(a -> !a.completedAt().isAfter(decision.at()))
                    .max(Comparator.comparing(AssessmentFact::completedAt));
            if (assessment.isEmpty()) continue;
            compared++;
            boolean aiShortlists = assessment.get().score() >= SHORTLIST_SCORE;
            if (aiShortlists == (decision.kind() == DecisionKind.SHORTLIST)) agreed++;
        }
        return compared == 0 ? OptionalDouble.empty() : OptionalDouble.of(Stats.pct(agreed, compared));
    }

    private OptionalDouble costPerScreened(String tenant, Range range, boolean previousPeriod) {
        long assessed = assessments.completedBetween(tenant, range.from(), range.to()).stream()
                .filter(AssessmentFact::complete)
                .map(AssessmentFact::applicationId)
                .distinct()
                .count();
        if (assessed == 0) return OptionalDouble.empty();
        double cost = aiDays
                .between(
                        tenant,
                        range.from().atZone(ZoneOffset.UTC).toLocalDate(),
                        range.to().atZone(ZoneOffset.UTC).toLocalDate().minusDays(previousPeriod ? 1 : 0))
                .stream()
                .mapToDouble(AiDayFact::costInr)
                .sum();
        return OptionalDouble.of(cost / assessed);
    }

    private static final java.time.Duration COHORT_TTL = java.time.Duration.ofSeconds(20);
    private static final int COHORT_CACHE_SIZE = 8;

    private record CachedCohort(List<ApplicationFact> facts, java.time.Instant loadedAt) {}

    private final Map<String, CachedCohort> cohorts = new java.util.LinkedHashMap<>();

    /**
     * The dashboard asks for six views at once and each reads the same applications, so they share one load for a
     * few seconds instead of each holding its own copy in memory.
     */
    private List<ApplicationFact> cohort(String tenant, String jobId, Range range) {
        var key = tenant
                + '|'
                + blankToNull(jobId)
                + '|'
                + range.from().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        synchronized (cohorts) {
            var now = clock.instant();
            var cached = cohorts.get(key);
            if (cached != null && cached.loadedAt().plus(COHORT_TTL).isAfter(now)) return cached.facts();
            var facts = applications.touchedSince(tenant, blankToNull(jobId), range.from()).stream()
                    .filter(fact -> range.contains(fact.submittedAt()))
                    .toList();
            cohorts.values().removeIf(entry -> entry.loadedAt().plus(COHORT_TTL).isBefore(now));
            while (cohorts.size() >= COHORT_CACHE_SIZE)
                cohorts.remove(cohorts.keySet().iterator().next());
            cohorts.put(key, new CachedCohort(facts, now));
            return facts;
        }
    }

    /** 0 applied, 1 AI assessed, 2 shortlisted, 3 interviewing, 4 offer, 5 hired; reaching a level implies the lower. */
    static int level(ApplicationFact fact) {
        if (fact.hiredAt() != null || fact.reached("HIRED")) return 5;
        if (fact.reached("OFFER")) return 4;
        if (fact.reached("INTERVIEWING") || fact.reached("DECISION_PENDING")) return 3;
        if (fact.reached("SHORTLISTED")) return 2;
        if (fact.screenedAt() != null || fact.reached("AI_ASSESSED")) return 1;
        return 0;
    }

    private String jobTitle(String tenant, String jobId, List<ApplicationFact> cohort) {
        if (jobId == null || jobId.isBlank()) return "All jobs";
        return titles.find(tenant, jobId)
                .or(() -> cohort.stream()
                        .map(ApplicationFact::jobTitle)
                        .filter(title -> title != null && !title.isBlank())
                        .findFirst())
                .orElse(jobId);
    }

    /** "+2.1 days", "-3 pts", "no change"; either period without data says so instead of inventing a number. */
    static String delta(OptionalDouble current, OptionalDouble previous, int decimals, String unit) {
        if (current.isEmpty()) return "no data this period";
        if (previous.isEmpty()) return "no prior data";
        double difference = Stats.round(current.getAsDouble() - previous.getAsDouble(), decimals);
        if (difference == 0) return "no change";
        var number = new java.math.BigDecimal(Math.abs(difference))
                .setScale(decimals, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        var label = unit.equals("day") && !number.equals("1") ? "days" : unit;
        return (difference > 0 ? "+" : "-") + number + " " + label;
    }

    private static boolean matches(String jobId, String actual) {
        return jobId == null || jobId.isBlank() || jobId.equals(actual);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String label(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
