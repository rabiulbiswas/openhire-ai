package com.openhire.analytics.application;

import static com.openhire.analytics.application.Events.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.analytics.application.AnalyticsViews.FunnelStage;
import com.openhire.analytics.application.AnalyticsViews.OverrideReason;
import com.openhire.analytics.application.AnalyticsViews.SourceQuality;
import com.openhire.analytics.application.AnalyticsViews.StageLatency;
import com.openhire.analytics.application.AnalyticsViews.TimeToHirePoint;
import com.openhire.analytics.application.Memory.MutableClock;
import com.openhire.analytics.application.Memory.Store;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class AnalyticsQueryServiceTest {
    private final Store store = new Store();
    private final MutableClock clock = new MutableClock(days(40));
    private final ProjectionService projections = store.projections(clock);
    private final AnalyticsQueryService analytics = store.queries(clock);

    private void apply(com.openhire.analytics.domain.Envelope... events) {
        for (var event : events)
            switch (event.eventType()) {
                case "assessment.completed.v1" -> projections.onAssessmentEvent(event);
                case "ai.request.completed.v1" -> projections.onAiEvent(event);
                default -> {
                    if (event.eventType().startsWith("offer.")) projections.onOfferEvent(event);
                    else projections.onApplicationEvent(event);
                }
            }
    }

    private void hire(String tenant, String app, String job, String source, Instant submitted, Instant hired) {
        apply(
                submitted(tenant, app, job, source, submitted),
                stage(tenant, app, "DECISION_PENDING", "OFFER", "u1", hired.minusSeconds(60)),
                stage(tenant, app, "OFFER", "HIRED", "employee-service", hired));
    }

    @Test
    void funnelCountsApplicationsAsReachedAtEachStage() {
        store.titles.put("t1|j1", "Backend Engineer");
        for (int i = 1; i <= 8; i++) apply(submitted("t1", "a" + i, "j1", "CAREER", days(15)));
        apply(submitted("t1", "x1", "j2", "CAREER", days(15)));
        for (int i = 1; i <= 6; i++) apply(screened("t1", "a" + i, days(15).plusSeconds(3600)));
        apply(screened("t1", "a8", days(15).plusSeconds(3600)));
        for (int i = 1; i <= 5; i++) apply(stage("t1", "a" + i, "AI_ASSESSED", "SHORTLISTED", "u1", days(16)));
        for (int i = 1; i <= 4; i++) apply(stage("t1", "a" + i, "SHORTLISTED", "INTERVIEWING", "u1", days(17)));
        for (int i = 1; i <= 4; i++) apply(stage("t1", "a" + i, "DECISION_PENDING", "OFFER", "u1", days(20)));
        for (int i = 1; i <= 3; i++) apply(stage("t1", "a" + i, "OFFER", "HIRED", "employee-service", days(22)));
        apply(stage("t1", "a8", "AI_ASSESSED", "REJECTED", "u1", days(16)));

        var funnel = analytics.funnel("t1", "j1", "30d");

        assertThat(funnel.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(funnel.stages())
                .containsExactly(
                        new FunnelStage("Applied", 8),
                        new FunnelStage("AI assessed", 7),
                        new FunnelStage("Shortlisted", 5),
                        new FunnelStage("Interviewing", 4),
                        new FunnelStage("Offer", 4),
                        new FunnelStage("Hired", 3));
        var all = analytics.funnel("t1", null, "30d");
        assertThat(all.jobTitle()).isEqualTo("All jobs");
        assertThat(all.stages().getFirst().value()).isEqualTo(9);
    }

    @Test
    void aStageImpliesTheEarlierOnesAndApplicationsOutsideTheRangeAreNotCounted() {
        apply(submitted("t1", "old", "j1", "CAREER", days(5)));
        apply(submitted("t1", "direct", "j1", "CAREER", days(20)));
        apply(stage("t1", "direct", "AI_ASSESSED", "SHORTLISTED", "u1", days(21)));

        var stages = analytics.funnel("t1", "j1", "30d").stages();

        assertThat(stages.stream().map(FunnelStage::value)).containsExactly(1L, 1L, 1L, 0L, 0L, 0L);
        assertThat(analytics.funnel("t1", "j1", "90d").stages().getFirst().value())
                .isEqualTo(2);
    }

    @Test
    void timeToHireIsTheMedianOfHiredApplicationsOnly() {
        hire("t1", "h1", "j1", "CAREER", days(15), days(22));
        hire("t1", "h2", "j1", "CAREER", days(16), days(26));
        hire("t1", "h3", "j1", "CAREER", days(17), days(37));
        apply(submitted("t1", "pending", "j1", "CAREER", days(12)));
        store.directoryDown = true;
        apply(stage("t1", "ghost", "OFFER", "HIRED", "employee-service", days(30)));

        assertThat(analytics.kpis("t1", "30d", null).medianTimeToHireDays()).isEqualTo(10.0);
        assertThat(analytics.timeToHire("t1", "30d", null))
                .containsExactly(new TimeToHirePoint("2026-W39", 8.5), new TimeToHirePoint("2026-W41", 20.0));
    }

    @Test
    void anEvenNumberOfHiresAveragesTheTwoMiddleOnes() {
        hire("t1", "h1", "j1", "CAREER", days(15), days(17));
        hire("t1", "h2", "j1", "CAREER", days(15), days(19));
        hire("t1", "h3", "j1", "CAREER", days(15), days(21));
        hire("t1", "h4", "j1", "CAREER", days(15), days(25));

        assertThat(analytics.kpis("t1", "30d", null).medianTimeToHireDays()).isEqualTo(5.0);
    }

    @Test
    void sourcesCountApplicantsShortlistRateAndHires() {
        var sources = new String[] {"CAREER", "CAREER", "AGENCY", "CAREER", "REFERRAL", "CAREER", "AGENCY", "CAREER"};
        for (int i = 0; i < sources.length; i++) apply(submitted("t1", "a" + (i + 1), "j1", sources[i], days(15)));
        for (int i = 1; i <= 5; i++) apply(stage("t1", "a" + i, "AI_ASSESSED", "SHORTLISTED", "u1", days(16)));
        for (int i = 1; i <= 3; i++) apply(stage("t1", "a" + i, "OFFER", "HIRED", "employee-service", days(22)));

        assertThat(analytics.sources("t1", "30d", null))
                .containsExactly(
                        new SourceQuality("Career site", 5, 60.0, 2),
                        new SourceQuality("Agency", 2, 50.0, 1),
                        new SourceQuality("Referral", 1, 100.0, 0));
    }

    @Test
    void overridesAreGroupedByReasonCode() {
        apply(submitted("t1", "a1", "j1", "CAREER", days(15)));
        apply(override("t1", "a1", "u1", "UNDERWEIGHTED_EVIDENCE", days(20)));
        apply(override("t1", "a1", "u1", "UNDERWEIGHTED_EVIDENCE", days(21)));
        apply(override("t1", "a1", "u1", null, days(22)));
        apply(override("t1", "a1", "u1", "CONTEXT_NOT_IN_RESUME", days(23)));
        apply(override("t1", "a1", "u1", "UNDERWEIGHTED_EVIDENCE", days(2)));

        assertThat(analytics.aiOverrides("t1", "30d", null))
                .containsExactly(
                        new OverrideReason("UNDERWEIGHTED_EVIDENCE", 2),
                        new OverrideReason("CONTEXT_NOT_IN_RESUME", 1),
                        new OverrideReason("OTHER", 1));
        assertThat(analytics.aiOverrides("t1", "1y", null).getFirst().value()).isEqualTo(3);
    }

    @Test
    void stageLatencyIsTheMedianAndNinetiethPercentileOfTimeSpentInEachStage() {
        long[] hours = {1, 2, 3, 4, 10};
        for (int i = 0; i < hours.length; i++) {
            var app = "a" + i;
            var submitted = days(15);
            apply(submitted("t1", app, "j1", "CAREER", submitted));
            apply(screened("t1", app, submitted.plus(Duration.ofHours(hours[i]))));
            apply(stage("t1", app, "AI_ASSESSED", "SHORTLISTED", "u1", submitted.plus(Duration.ofDays(1))));
        }

        assertThat(analytics.stageLatency("t1", "30d", null))
                .containsExactly(new StageLatency("APPLIED", 3.0, 10.0), new StageLatency("AI_ASSESSED", 21.0, 23.0));
    }

    @Test
    void tenantsNeverSeeEachOthersNumbers() {
        hire("t1", "h1", "j1", "CAREER", days(15), days(22));
        apply(override("t1", "h1", "u1", "OTHER", days(20)));
        hire("t2", "h1", "j1", "AGENCY", days(15), days(35));
        hire("t2", "h2", "j1", "AGENCY", days(15), days(33));
        apply(override("t2", "h1", "u9", "OVERWEIGHTED_GAP", days(20)));
        apply(override("t2", "h2", "u9", "OVERWEIGHTED_GAP", days(20)));

        assertThat(analytics.funnel("t1", null, "30d").stages().getFirst().value())
                .isEqualTo(1);
        assertThat(analytics.funnel("t2", null, "30d").stages().getFirst().value())
                .isEqualTo(2);
        assertThat(analytics.sources("t1", "30d", null)).extracting("source").containsExactly("Career site");
        assertThat(analytics.sources("t2", "30d", null)).extracting("source").containsExactly("Agency");
        assertThat(analytics.aiOverrides("t1", "30d", null)).containsExactly(new OverrideReason("OTHER", 1));
        assertThat(analytics.aiOverrides("t2", "30d", null)).containsExactly(new OverrideReason("OVERWEIGHTED_GAP", 2));
        assertThat(analytics.kpis("t1", "30d", null).medianTimeToHireDays()).isEqualTo(7.0);
        assertThat(analytics.kpis("t2", "30d", null).medianTimeToHireDays()).isEqualTo(19.0);
        assertThat(analytics.kpis("t3", "30d", null).medianTimeToHireDays()).isZero();
    }

    @Test
    void noDataGivesZerosAndEmptyListsNeverErrors() {
        var kpis = analytics.kpis("t1", "30d", null);

        assertThat(kpis.medianTimeToHireDays()).isZero();
        assertThat(kpis.offerAcceptancePct()).isZero();
        assertThat(kpis.aiShortlistAgreementPct()).isZero();
        assertThat(kpis.aiCostPerScreenedApplicant()).isZero();
        assertThat(kpis.medianTimeToHireDelta()).isEqualTo("no data this period");
        assertThat(kpis.currency()).isEqualTo("INR");
        assertThat(analytics.funnel("t1", null, "30d").stages())
                .extracting("value")
                .containsOnly(0L);
        assertThat(analytics.timeToHire("t1", "30d", null)).isEmpty();
        assertThat(analytics.sources("t1", "30d", null)).isEmpty();
        assertThat(analytics.aiOverrides("t1", "30d", null)).isEmpty();
        assertThat(analytics.stageLatency("t1", "30d", null)).isEmpty();
    }

    @Test
    void anUnknownRangeIsRejected() {
        assertThatThrownBy(() -> analytics.kpis("t1", "7d", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deltaStringsNameDirectionSizeAndUnit() {
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(10), OptionalDouble.of(7.9), 1, "day"))
                .isEqualTo("+2.1 days");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(7.9), OptionalDouble.of(8.9), 1, "day"))
                .isEqualTo("-1 day");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(60), OptionalDouble.of(75), 1, "pts"))
                .isEqualTo("-15 pts");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(3.0), OptionalDouble.of(1.5), 2, "INR"))
                .isEqualTo("+1.5 INR");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(5), OptionalDouble.of(5.04), 1, "pts"))
                .isEqualTo("no change");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.of(5), OptionalDouble.empty(), 1, "pts"))
                .isEqualTo("no prior data");
        assertThat(AnalyticsQueryService.delta(OptionalDouble.empty(), OptionalDouble.of(5), 1, "pts"))
                .isEqualTo("no data this period");
    }

    /**
     * A seeded set of journeys, run through the projections, yields exactly the numbers worked out by hand for the
     * definitions in {@link AnalyticsQueryService}: current period (days 10..40) against the one before it.
     */
    @Test
    void reconciliationOfSeededJourneysYieldsTheExpectedKpis() {
        // Hires: current 7, 10 and 20 days (median 10); previous 7 and 8.8 days (median 7.9).
        hire("t1", "c1", "j1", "CAREER", days(15), days(22));
        hire("t1", "c2", "j1", "CAREER", days(16), days(26));
        hire("t1", "c3", "j1", "CAREER", days(17), days(37));
        hire("t1", "p1", "j1", "CAREER", days(-12), days(-12).plusSeconds(760_320));
        hire("t1", "p2", "j1", "CAREER", days(-9), days(-2));

        // Offers: current 3 accepted, 1 declined, 1 expired (60 percent); previous 1 accepted, 1 declined (50).
        for (int i = 1; i <= 3; i++) apply(offer("offer.accepted.v1", "t1", "o" + i, "c" + i, "j1", days(20 + i)));
        apply(offer("offer.declined.v1", "t1", "o4", "d4", "j1", days(25)));
        apply(offer("offer.expired.v1", "t1", "o5", "d5", "j1", days(28)));
        apply(offer("offer.accepted.v1", "t1", "o6", "p1", "j1", days(-5)));
        apply(offer("offer.declined.v1", "t1", "o7", "p2", "j1", days(-4)));
        apply(offer("offer.viewed.v1", "t1", "o8", "x", "j1", days(30)));

        // Agreement, current: d1..d5 compared, 3 agree (60 percent). d6 partial, d7 assessed after the decision,
        // d8 undone and d9 an interview-stage rejection are not comparable.
        screening("d1", 80, "SHORTLISTED", 20);
        screening("d2", 70, "SHORTLISTED", 21);
        screening("d3", 30, "REJECTED", 22);
        screening("d4", 75, "REJECTED", 23);
        screening("d5", 20, "SHORTLISTED", 24);
        apply(submitted("t1", "d6", "j1", "CAREER", days(15)));
        apply(assessment("t1", "d6", "as-d6", "PARTIAL", 90, "LOW", days(18)));
        apply(stage("t1", "d6", "AI_ASSESSED", "SHORTLISTED", "u1", days(19)));
        apply(submitted("t1", "d7", "j1", "CAREER", days(15)));
        apply(stage("t1", "d7", "AI_ASSESSED", "SHORTLISTED", "u1", days(19)));
        apply(assessment("t1", "d7", "as-d7", "COMPLETE", 90, "HIGH", days(20)));
        screening("d8", 80, "REJECTED", 25);
        apply(event("ApplicationDecisionUndone", "t1", "d8", days(26), map("actorId", "u1")));
        apply(submitted("t1", "d9", "j1", "CAREER", days(15)));
        apply(assessment("t1", "d9", "as-d9", "COMPLETE", 90, "HIGH", days(18)));
        apply(stage("t1", "d9", "DECISION_PENDING", "REJECTED", "u1", days(27)));
        // Agreement, previous: 3 of 4 agree (75 percent).
        previous("q1", 90, "SHORTLISTED", -8);
        previous("q2", 80, "SHORTLISTED", -7);
        previous("q3", 10, "REJECTED", -6);
        previous("q4", 10, "SHORTLISTED", -5);

        // Cost: current 24 INR over 8 assessed applications (d1..d5, d7, d8, d9) is 3.00; previous 6 INR over the 4
        // applications assessed then (q1..q4) is 1.50.
        apply(ai("t1", "judge", "gpt", "OK", 400, 20.0, days(22)));
        apply(ai("t1", "judge", "gpt", "OK", 400, 4.0, days(30)));
        apply(ai("t1", "judge", "gpt", "OK", 400, 6.0, days(-3)));
        apply(ai("t2", "judge", "gpt", "OK", 400, 500.0, days(30)));

        var kpis = analytics.kpis("t1", "30d", null);

        assertThat(kpis.medianTimeToHireDays()).isEqualTo(10.0);
        assertThat(kpis.medianTimeToHireDelta()).isEqualTo("+2.1 days");
        assertThat(kpis.offerAcceptancePct()).isEqualTo(60.0);
        assertThat(kpis.offerAcceptanceDelta()).isEqualTo("+10 pts");
        assertThat(kpis.aiShortlistAgreementPct()).isEqualTo(60.0);
        assertThat(kpis.aiShortlistAgreementDelta()).isEqualTo("-15 pts");
        assertThat(kpis.aiCostPerScreenedApplicant()).isEqualTo(3.0);
        assertThat(kpis.aiCostDelta()).isEqualTo("+1.5 INR");
        assertThat(kpis.currency()).isEqualTo("INR");

        var forJob = analytics.kpis("t1", "30d", "j1");
        assertThat(forJob.medianTimeToHireDays()).isEqualTo(10.0);
        assertThat(forJob.aiShortlistAgreementPct()).isEqualTo(60.0);
        var otherJob = analytics.kpis("t1", "30d", "j-other");
        assertThat(otherJob.medianTimeToHireDays()).isZero();
        assertThat(otherJob.offerAcceptancePct()).isZero();
        assertThat(otherJob.aiShortlistAgreementPct()).isZero();
        // Another tenant's spend never leaks in.
        assertThat(analytics.kpis("t2", "30d", null).aiCostPerScreenedApplicant())
                .isZero();
    }

    private void screening(String app, int score, String outcome, int day) {
        apply(submitted("t1", app, "j1", "CAREER", days(15)));
        apply(assessment(
                "t1", app, "as-" + app, "COMPLETE", score, "HIGH", days(day).minusSeconds(3600)));
        apply(stage("t1", app, "AI_ASSESSED", outcome, "u1", days(day)));
    }

    private void previous(String app, int score, String outcome, int day) {
        apply(submitted("t1", app, "j1", "CAREER", days(-15)));
        apply(assessment(
                "t1", app, "as-" + app, "COMPLETE", score, "HIGH", days(day).minusSeconds(3600)));
        apply(stage("t1", app, "AI_ASSESSED", outcome, "u1", days(day)));
    }
}
