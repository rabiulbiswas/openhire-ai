package com.openhire.analytics.service;

import static com.openhire.analytics.service.Events.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.Envelope;
import com.openhire.analytics.domain.Facts.DecisionKind;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.service.Memory.MutableClock;
import com.openhire.analytics.service.Memory.Store;
import com.openhire.analytics.service.port.AnalyticsPorts.ApplicationDirectory.ApplicationInfo;
import com.openhire.analytics.service.port.AnalyticsPorts.ApplicationFacts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectionServiceTest {
    private final Store store = new Store();
    private final MutableClock clock = new MutableClock(days(30));
    private final ProjectionService projections = store.projections(clock);

    private ApplicationFact fact(String app) {
        return store.app(app).orElseThrow();
    }

    @Test
    void aJourneyBecomesAStageTimelineWithTheHumanDecisionRecorded() {
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onApplicationEvent(screened("t1", "a1", days(0).plusSeconds(3600)));
        projections.onApplicationEvent(stage("t1", "a1", "AI_ASSESSED", "SHORTLISTED", "u1", days(1)));
        projections.onApplicationEvent(stage("t1", "a1", "SHORTLISTED", "INTERVIEWING", "u1", days(2)));
        projections.onApplicationEvent(stage("t1", "a1", "DECISION_PENDING", "OFFER", "u2", days(5)));
        projections.onApplicationEvent(stage("t1", "a1", "OFFER", "HIRED", "employee-service", days(9)));

        var fact = fact("a1");
        assertThat(fact.jobId()).isEqualTo("j1");
        assertThat(fact.source()).isEqualTo("CAREER");
        assertThat(fact.submittedAt()).isEqualTo(days(0));
        assertThat(fact.screenedAt()).isEqualTo(days(0).plusSeconds(3600));
        assertThat(fact.timeline().stream().map(ApplicationFact.StageEntry::stage))
                .containsExactly("APPLIED", "AI_ASSESSED", "SHORTLISTED", "INTERVIEWING", "OFFER", "HIRED");
        assertThat(fact.hiredAt()).isEqualTo(days(9));
        assertThat(store.decisions.values()).singleElement().satisfies(decision -> {
            assertThat(decision.kind()).isEqualTo(DecisionKind.SHORTLIST);
            assertThat(decision.actorType()).isEqualTo("USER");
            assertThat(decision.actorId()).isEqualTo("u1");
            assertThat(decision.fromStage()).isEqualTo("AI_ASSESSED");
            assertThat(decision.at()).isEqualTo(days(1));
            assertThat(decision.jobId()).isEqualTo("j1");
        });
    }

    @Test
    void redeliveringTheSameEventsChangesNothing() {
        var shortlist = stage("t1", "a1", "AI_ASSESSED", "SHORTLISTED", "u1", days(1));
        var request = ai("t1", "judge", "qwen", "OK", 120, 0.5, days(1));
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        for (int i = 0; i < 3; i++) {
            projections.onApplicationEvent(shortlist);
            projections.onAiEvent(request);
        }

        assertThat(fact("a1").timeline()).hasSize(2);
        assertThat(store.decisions).hasSize(1);
        assertThat(store.aiDays.values()).singleElement().satisfies(day -> {
            assertThat(day.requests()).isEqualTo(1);
            assertThat(day.costInr()).isEqualTo(0.5);
        });
    }

    @Test
    void rejectionWithdrawalAndAttentionAreRecorded() {
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "AGENCY", days(0)));
        projections.onApplicationEvent(
                event("ApplicationNeedsAttention", "t1", "a1", days(0).plusSeconds(60), map("code", "X")));
        projections.onApplicationEvent(stage("t1", "a1", "NEEDS_ATTENTION", "REJECTED", "u1", days(2)));
        projections.onApplicationEvent(submitted("t1", "a2", "j1", "AGENCY", days(0)));
        projections.onApplicationEvent(stage("t1", "a2", "APPLIED", "WITHDRAWN", "candidate-a2", days(3)));

        assertThat(fact("a1").rejectedAt()).isEqualTo(days(2));
        assertThat(fact("a1").reached("NEEDS_ATTENTION")).isTrue();
        assertThat(fact("a2").withdrawnAt()).isEqualTo(days(3));
        // The candidate's own withdrawal is not a recruiter decision; the rejection is.
        assertThat(store.decisions.values()).extracting("kind").containsExactly(DecisionKind.REJECT);
    }

    @Test
    void undoingADecisionRemovesItFromTheJourney() {
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onApplicationEvent(stage("t1", "a1", "APPLIED", "SHORTLISTED", "u1", days(1)));
        projections.onApplicationEvent(event("ApplicationDecisionUndone", "t1", "a1", days(1), map("actorId", "u1")));

        assertThat(fact("a1").reached("SHORTLISTED")).isFalse();
        assertThat(store.decisions.values()).singleElement().satisfies(d -> assertThat(d.undone())
                .isTrue());
    }

    @Test
    void overrideReasonDefaultsToOtherAndRejectsUnknownCodes() {
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onApplicationEvent(override("t1", "a1", "u1", null, days(1)));
        projections.onApplicationEvent(override("t1", "a1", "u1", "UNDERWEIGHTED_EVIDENCE", days(2)));
        projections.onApplicationEvent(override("t1", "a1", "u1", "made-up", days(3)));
        projections.onApplicationEvent(override("t1", "a1", "u1", "overweighted_gap", days(4)));

        assertThat(store.decisions.values())
                .extracting("reasonCode")
                .containsExactly("OTHER", "UNDERWEIGHTED_EVIDENCE", "OTHER", "OVERWEIGHTED_GAP");
        assertThat(store.decisions.values()).allSatisfy(d -> {
            assertThat(d.kind()).isEqualTo(DecisionKind.OVERRIDE);
            assertThat(d.newScore()).isEqualTo(55);
        });
    }

    @Test
    void serviceAccountsAreNotHumanDecisions() {
        assertThat(ProjectionService.human("u1")).isTrue();
        assertThat(ProjectionService.human("employee-service")).isFalse();
        assertThat(ProjectionService.human("system")).isFalse();
        assertThat(ProjectionService.human("")).isFalse();
        assertThat(ProjectionService.human(null)).isFalse();
    }

    @Test
    void unknownEventsAndMissingFieldsAreSkippedNotFailed() {
        assertThatCode(() -> {
                    projections.onApplicationEvent(event("SomethingNew", "t1", "a1", days(0), map()));
                    projections.onApplicationEvent(event("ApplicationStageChanged", "t1", "a1", days(0), map()));
                    projections.onApplicationEvent(event("ApplicationSubmitted", "", "a1", days(0), map()));
                    projections.onApplicationEvent(event("ApplicationSubmitted", "t1", "", days(0), map()));
                    projections.onAssessmentEvent(event("assessment.completed.v1", "t1", "x", days(0), map()));
                    projections.onAssessmentEvent(event("assessment.unknown.v1", "t1", "x", days(0), map()));
                    projections.onOfferEvent(event("offer.accepted.v1", "t1", "o", days(0), map()));
                    projections.onResumeEvent(event("ResumeProcessed", "t1", "r", days(0), map()));
                    projections.onAiEvent(event("ai.request.completed.v1", "", "x", days(0), map()));
                    projections.onAiEvent(new Envelope("", "ai.request.completed.v1", "t1", "x", days(0), map()));
                })
                .doesNotThrowAnyException();

        assertThat(store.applications).isEmpty();
        assertThat(store.assessments).isEmpty();
        assertThat(store.offers).isEmpty();
        assertThat(store.aiDays).isEmpty();
        assertThat(store.latencies).isEmpty();
    }

    @Test
    void assessmentsKeepScoreConfidenceModelAndTheScreeningTime() {
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onApplicationEvent(screened("t1", "a1", days(0).plusSeconds(10)));
        projections.onAssessmentEvent(assessment("t1", "a1", "as1", "PARTIAL", 40, "LOW", days(0).plusSeconds(20)));
        projections.onAssessmentEvent(assessment("t1", "a1", "as2", "COMPLETE", 78, "HIGH", days(0).plusSeconds(50)));

        assertThat(store.assessments.values()).hasSize(2);
        var complete = store.assessments.get("t1|as2");
        assertThat(complete.score()).isEqualTo(78);
        assertThat(complete.confidence()).isEqualTo("HIGH");
        assertThat(complete.model()).isEqualTo("qwen2.5:3b");
        assertThat(complete.status()).isEqualTo("COMPLETE");
        assertThat(complete.completedAt()).isEqualTo(days(0).plusSeconds(50));
        assertThat(complete.screenedAt()).isEqualTo(days(0).plusSeconds(10));
        // Only the COMPLETE assessment ends the AI assessment clock.
        var latency = store.latencies.get("t1|AI_ASSESSMENT|a1");
        assertThat(latency.duration()).contains(Duration.ofSeconds(40));
    }

    @Test
    void offersRecordEachLifecycleTime() {
        projections.onOfferEvent(offer("offer.created.v1", "t1", "o1", "a1", "j1", days(1)));
        projections.onOfferEvent(offer("offer.sent.v1", "t1", "o1", "a1", "j1", days(2)));
        projections.onOfferEvent(offer("offer.accepted.v1", "t1", "o1", "a1", "j1", days(3)));
        projections.onOfferEvent(offer("offer.created.v1", "t1", "o2", "a2", "j1", days(1)));
        projections.onOfferEvent(offer("offer.declined.v1", "t1", "o2", "a2", "j1", days(4)));
        projections.onOfferEvent(offer("offer.viewed.v1", "t1", "o2", "a2", "j1", days(5)));

        var accepted = store.offers.get("t1|o1");
        assertThat(accepted.createdAt()).isEqualTo(days(1));
        assertThat(accepted.sentAt()).isEqualTo(days(2));
        assertThat(accepted.acceptedAt()).isEqualTo(days(3));
        assertThat(accepted.jobId()).isEqualTo("j1");
        assertThat(store.offers.get("t1|o2").declinedAt()).isEqualTo(days(4));
        assertThat(store.titles).containsEntry("t1|j1", "Engineer j1");
    }

    @Test
    void aiRequestsAggregatePerDayTaskAndModel() {
        projections.onAiEvent(ai("t1", "judge", "qwen", "OK", 100, 0.5, days(1).plusSeconds(10)));
        projections.onAiEvent(ai("t1", "judge", "qwen", "PROVIDER_ERROR", 900, 0.25, days(1).plusSeconds(20)));
        projections.onAiEvent(ai("t1", "judge", "qwen", "CACHED", 25_000, 0, days(1).plusSeconds(30)));
        projections.onAiEvent(ai("t1", "embed", "qwen", "OK", 50, 0, days(1).plusSeconds(40)));
        projections.onAiEvent(ai("t1", "judge", "qwen", "OK", 100, 1.0, days(2).minusSeconds(1)));
        projections.onAiEvent(ai("t1", "judge", "qwen", "OK", 100, 2.0, days(2)));

        var day1 = store.aiDays.get("t1|2026-09-02|judge|qwen");
        assertThat(day1.requests()).isEqualTo(4);
        assertThat(day1.errors()).isEqualTo(1);
        assertThat(day1.promptTokens()).isEqualTo(400);
        assertThat(day1.completionTokens()).isEqualTo(80);
        assertThat(day1.costInr()).isEqualTo(1.75);
        assertThat(day1.latencyMsSum()).isEqualTo(100 + 900 + 25_000 + 100);
        // Four requests: the 95th percentile is the slowest, in the 20..30 s bucket.
        assertThat(day1.p95Ms()).isEqualTo(30_000);
        assertThat(store.aiDays).hasSize(3);
        assertThat(store.aiDays.get("t1|2026-09-03|judge|qwen").requests()).isEqualTo(1);
    }

    @Test
    void latenciesPairStartAndEndInEitherOrder() {
        projections.onDeliveryEvent(event(
                "communication.delivery.updated.v1",
                "t1",
                "m1",
                days(0).plusSeconds(8),
                map("messageId", "m1", "status", "SENT")));
        projections.onCommunicationCommand(
                event("communication.message.requested.v1", "t1", "m1", days(0), map("messageId", "m1")));
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onResumeEvent(
                event("ResumeProcessed", "t1", "r1", days(0).plusSeconds(25), map("applicationId", "a1")));
        projections.onResumeEvent(
                event("ResumeProcessingFailed", "t1", "r2", days(0).plusSeconds(1), map("applicationId", "a1")));

        assertThat(store.latencies.get("t1|NOTIFICATION_HANDOFF|m1").duration()).contains(Duration.ofSeconds(8));
        assertThat(store.latencies.get("t1|RESUME_PROCESSING|a1").duration()).contains(Duration.ofSeconds(25));
        assertThat(store.latencies.keySet()).noneMatch(key -> key.contains("AI_ASSESSMENT"));
        assertThat(LatencyKind.values()).hasSize(3);
    }

    @Test
    void theJobTitleIsFetchedOnceThenCachedAndAnOutageDoesNotFailTheEvent() {
        store.directory.put("t1|a1", new ApplicationInfo("j1", "Senior Engineer", "CAREER", days(0)));
        projections.onApplicationEvent(submitted("t1", "a1", "j1", "CAREER", days(0)));
        projections.onApplicationEvent(submitted("t1", "a2", "j1", "CAREER", days(0)));

        assertThat(fact("a1").jobTitle()).isEqualTo("Senior Engineer");
        assertThat(fact("a2").jobTitle()).isEqualTo("Senior Engineer");
        assertThat(store.directoryCalls).isEqualTo(1);

        store.directoryDown = true;
        assertThatCode(() -> projections.onApplicationEvent(submitted("t1", "a3", "j9", "CAREER", days(0))))
                .doesNotThrowAnyException();
        assertThat(fact("a3").jobTitle()).isNull();
        assertThat(fact("a3").jobId()).isEqualTo("j9");
    }

    @Test
    void anApplicationFirstSeenMidJourneyIsCompletedFromTheProjection() {
        store.directory.put("t1|a1", new ApplicationInfo("j1", "Engineer", "REFERRAL", days(0)));
        projections.onApplicationEvent(stage("t1", "a1", "APPLIED", "SHORTLISTED", "u1", days(1)));

        var fact = fact("a1");
        assertThat(fact.jobId()).isEqualTo("j1");
        assertThat(fact.source()).isEqualTo("REFERRAL");
        assertThat(fact.submittedAt()).isEqualTo(days(0));
        assertThat(store.decisions.values()).singleElement().satisfies(d -> assertThat(d.jobId())
                .isEqualTo("j1"));
    }

    @Test
    void aFailedProjectionReleasesTheClaimSoTheRedeliveryIsApplied() {
        boolean[] failing = {true};
        ApplicationFacts flaky = new ApplicationFacts() {
            @Override
            public Optional<ApplicationFact> find(String tenantId, String applicationId) {
                return store.apps.find(tenantId, applicationId);
            }

            @Override
            public void save(ApplicationFact fact) {
                if (failing[0]) throw new IllegalStateException("mongo down");
                store.apps.save(fact);
            }

            @Override
            public List<ApplicationFact> touchedSince(String tenantId, String jobId, Instant since) {
                return store.apps.touchedSince(tenantId, jobId, since);
            }
        };
        var service = new ProjectionService(
                store, flaky, store, store, store.offerPort, store, store, store.titlePort, store, clock);
        var event = submitted("t1", "a1", "j1", "CAREER", days(0));

        assertThatThrownBy(() -> service.onApplicationEvent(event)).isInstanceOf(IllegalStateException.class);
        failing[0] = false;
        service.onApplicationEvent(event);

        assertThat(fact("a1").submittedAt()).isEqualTo(days(0));
    }
}
