package com.openhire.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.Rating;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.RoundPlan;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import com.openhire.interview.domain.InterviewTypes.Slot;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InterviewLoopTest {
    static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");
    static final Duration FEEDBACK = Duration.ofHours(24);
    static final PanelMember ASHA = new PanelMember("Asha Rao", "asha@example.test", "LEAD");
    static final PanelMember BEN = new PanelMember("Ben Ortiz", "ben@example.test", "LEAD");
    static final Viewer ASHA_VIEW = new Viewer("sub-asha", "asha@example.test", "Asha Rao", Set.of("INTERVIEWER"));
    static final Viewer BEN_VIEW = new Viewer("sub-ben", "BEN@example.test", "Ben Ortiz", Set.of("INTERVIEWER"));
    static final Viewer OUTSIDER = new Viewer("sub-x", "x@example.test", "Someone Else", Set.of("INTERVIEWER"));
    static final Viewer RECRUITER = new Viewer("sub-r", "r@example.test", "Recruiter", Set.of("RECRUITER"));

    static InterviewLoop loop() {
        return InterviewLoop.create(
                "tenant-a",
                "app-1",
                "cand-1",
                "Priya Das",
                "job-1",
                "Backend Engineer",
                "sub-r",
                List.of(
                        new RoundPlan("Technical", 60, List.of("Asha Rao")),
                        new RoundPlan("System design", 90, List.of())),
                List.of(new Competency("c1", "Problem solving"), new Competency("c2", "Communication")),
                NOW);
    }

    static Slot slot(Instant start, int minutes) {
        return new Slot(start, start.plus(Duration.ofMinutes(minutes)), "Asia/Kolkata");
    }

    static String first(InterviewLoop loop) {
        return loop.rounds().getFirst().id();
    }

    static List<RatingEntry> ratings(Rating a, Rating b) {
        return List.of(new RatingEntry("c1", a), new RatingEntry("c2", b));
    }

    @Test
    void createsOneUnscheduledRoundPerPlannedRoundWithTheRubricAsCompetencies() {
        var loop = loop();

        assertThat(loop.id()).isEqualTo(InterviewLoop.idFor("tenant-a", "app-1"));
        assertThat(loop.rounds()).extracting(InterviewRound::name).containsExactly("Technical", "System design");
        assertThat(loop.rounds()).allMatch(round -> round.status() == RoundStatus.UNSCHEDULED);
        assertThat(loop.rounds().getFirst().panel())
                .extracting(PanelMember::name)
                .containsExactly("Asha Rao");
        assertThat(loop.rounds().getFirst().competencies()).hasSize(2);
        assertThat(loop.pendingEvents())
                .extracting(InterviewTypes.PendingEvent::eventType)
                .containsExactly("interview.loop-created.v1");
    }

    @Test
    void schedulingRequiresTheRoundDurationAndAPanel() {
        var loop = loop();
        var round = first(loop);

        assertThatThrownBy(() -> loop.schedule(
                        round, slot(NOW.plus(Duration.ofDays(1)), 30), List.of(ASHA), null, FEEDBACK, NOW))
                .hasMessageContaining("60 minutes");
        assertThatThrownBy(() ->
                        loop.schedule(round, slot(NOW.plus(Duration.ofDays(1)), 60), List.of(), null, FEEDBACK, NOW))
                .hasMessageContaining("interviewer");

        loop.schedule(
                round,
                slot(NOW.plus(Duration.ofDays(1)), 60),
                List.of(ASHA, BEN),
                "https://meet.example/x",
                FEEDBACK,
                NOW);
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.SCHEDULED);
        assertThat(loop.round(round).feedbackDueAt())
                .isEqualTo(NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(25)));
        assertThat(loop.pendingEvents())
                .extracting(InterviewTypes.PendingEvent::eventType)
                .contains("interview.scheduled.v1");
    }

    @Test
    void thePanelCanBeAssignedBeforeBookingButNotChangedOnALiveBooking() {
        var loop = loop();
        var round = first(loop);

        loop.assignPanel(round, List.of(ASHA, BEN), NOW);
        assertThat(loop.round(round).panel()).containsExactly(ASHA, BEN);
        assertThat(loop.pendingEvents()).hasSize(1);
        loop.schedule(
                round, slot(NOW.plus(Duration.ofDays(1)), 60), loop.round(round).panel(), null, FEEDBACK, NOW);
        assertThatThrownBy(() -> loop.assignPanel(round, List.of(ASHA), NOW)).hasMessageContaining("rescheduling");
    }

    @Test
    void bookingAfterACancellationIsANewBookingForTheCandidate() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(round, slot(NOW.plus(Duration.ofDays(1)), 60), List.of(ASHA), null, FEEDBACK, NOW);
        loop.cancel(round, "Replanning", NOW);
        loop.schedule(round, slot(NOW.plus(Duration.ofDays(2)), 60), List.of(ASHA), null, FEEDBACK, NOW);

        assertThat(loop.pendingEvents())
                .extracting(InterviewTypes.PendingEvent::eventType)
                .containsExactly(
                        "interview.loop-created.v1",
                        "interview.scheduled.v1",
                        "interview.cancelled.v1",
                        "interview.scheduled.v1");
    }

    @Test
    void reschedulingIsLimitedAndMustBeInTheFuture() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(round, slot(NOW.plus(Duration.ofDays(1)), 60), List.of(ASHA), null, FEEDBACK, NOW);

        assertThatThrownBy(() -> loop.reschedule(round, slot(NOW.minus(Duration.ofHours(1)), 60), 2, FEEDBACK, NOW))
                .hasMessageContaining("future");
        loop.reschedule(round, slot(NOW.plus(Duration.ofDays(2)), 60), 2, FEEDBACK, NOW);
        loop.reschedule(round, slot(NOW.plus(Duration.ofDays(3)), 60), 2, FEEDBACK, NOW);
        assertThatThrownBy(() -> loop.reschedule(round, slot(NOW.plus(Duration.ofDays(4)), 60), 2, FEEDBACK, NOW))
                .hasMessageContaining("rescheduled 2 times");
        assertThat(loop.round(round).rescheduleCount()).isEqualTo(2);
    }

    @Test
    void noShowAndCompletionOnlyAfterTheRoundStarts() {
        var loop = loop();
        var round = first(loop);
        var start = NOW.plus(Duration.ofHours(3));
        loop.schedule(round, slot(start, 60), List.of(ASHA), null, FEEDBACK, NOW);

        assertThatThrownBy(() -> loop.markNoShow(round, NOW)).hasMessageContaining("not started");
        assertThatThrownBy(() -> loop.complete(round, NOW)).hasMessageContaining("not started");
        loop.markNoShow(round, start.plus(Duration.ofMinutes(10)));
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.NO_SHOW);

        loop.schedule(round, slot(NOW.plus(Duration.ofDays(2)), 60), List.of(ASHA), null, FEEDBACK, NOW);
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.SCHEDULED);
        assertThat(loop.round(round).rescheduleCount()).isEqualTo(1);
    }

    @Test
    void scorecardsAreBlindUntilTheViewerSubmitsTheirOwn() {
        var loop = loop();
        var round = first(loop);
        var start = NOW.minus(Duration.ofHours(2));
        loop.schedule(round, slot(start, 60), List.of(ASHA, BEN), null, FEEDBACK, NOW.minus(Duration.ofDays(1)));

        assertThatThrownBy(() ->
                        loop.submitScorecard(round, OUTSIDER, ratings(Rating.GOOD, Rating.GOOD), "Evidence", null, NOW))
                .hasMessageContaining("interviewers");
        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.EXCELLENT, Rating.GOOD), "Clear trade-offs", null, NOW);

        assertThat(loop.visibleScorecards(round, BEN_VIEW)).isEmpty();
        assertThat(loop.visibleScorecards(round, ASHA_VIEW)).hasSize(1);
        assertThat(loop.visibleScorecards(round, RECRUITER)).hasSize(1);
        assertThatThrownBy(() -> loop.visibleScorecards(round, OUTSIDER)).isInstanceOf(IllegalStateException.class);

        loop.submitScorecard(round, BEN_VIEW, ratings(Rating.MIXED, Rating.GOOD), "Struggled with caching", null, NOW);
        assertThat(loop.visibleScorecards(round, BEN_VIEW)).hasSize(2);
        assertThat(loop.round(round).feedbackComplete()).isTrue();
    }

    @Test
    void aScorecardIsLockedOnceSubmittedAndMustRateEveryCompetency() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(
                round,
                slot(NOW.minus(Duration.ofHours(2)), 60),
                List.of(ASHA),
                null,
                FEEDBACK,
                NOW.minus(Duration.ofDays(1)));

        assertThatThrownBy(() -> loop.submitScorecard(
                        round, ASHA_VIEW, List.of(new RatingEntry("c1", Rating.GOOD)), "e", null, NOW))
                .hasMessageContaining("every competency");
        assertThatThrownBy(
                        () -> loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.GOOD, Rating.GOOD), " ", null, NOW))
                .hasMessageContaining("Evidence");
        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.GOOD, Rating.GOOD), "Solid", null, NOW);
        assertThatThrownBy(() ->
                        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.POOR, Rating.POOR), "Changed", null, NOW))
                .hasMessageContaining("locked");
    }

    @Test
    void scorecardsCannotBeSubmittedBeforeTheRoundStarts() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(round, slot(NOW.plus(Duration.ofHours(5)), 60), List.of(ASHA), null, FEEDBACK, NOW);

        assertThatThrownBy(() ->
                        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.GOOD, Rating.GOOD), "Early", null, NOW))
                .hasMessageContaining("started");
    }

    @Test
    void sweepCompletesEndedRoundsAndFlagsLateFeedbackOnce() {
        var loop = loop();
        var round = first(loop);
        var start = NOW.plus(Duration.ofHours(3));
        loop.schedule(round, slot(start, 60), List.of(ASHA, BEN), null, FEEDBACK, NOW);

        assertThat(loop.sweep(start.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(loop.sweep(start.plus(Duration.ofMinutes(61)))).isTrue();
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.COMPLETED);

        var late = start.plus(Duration.ofHours(26));
        loop.submitScorecard(
                round, ASHA_VIEW, ratings(Rating.GOOD, Rating.GOOD), "Fine", null, late.minus(Duration.ofHours(2)));
        assertThat(loop.sweep(late)).isTrue();
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.FEEDBACK_OVERDUE);
        assertThat(loop.sweep(late.plusSeconds(60))).isFalse();
        var overdue = loop.pendingEvents().getLast();
        assertThat(overdue.eventType()).isEqualTo("interview.feedback-overdue.v1");
        assertThat(overdue.payload().get("missingInterviewers").toString())
                .contains("ben@example.test")
                .doesNotContain("asha");

        loop.submitScorecard(round, BEN_VIEW, ratings(Rating.GOOD, Rating.GOOD), "Fine", null, late.plusSeconds(120));
        assertThat(loop.round(round).status()).isEqualTo(RoundStatus.COMPLETED);
    }

    @Test
    void closingCancelsRoundsThatHaveNotHappenedAndBlocksNewScheduling() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(round, slot(NOW.plus(Duration.ofDays(1)), 60), List.of(ASHA), null, FEEDBACK, NOW);

        assertThat(loop.close("The candidate withdrew", NOW)).isTrue();
        assertThat(loop.close("again", NOW)).isFalse();
        assertThat(loop.rounds()).allMatch(value -> value.status() == RoundStatus.CANCELLED);
        assertThatThrownBy(() -> loop.schedule(
                        round, slot(NOW.plus(Duration.ofDays(2)), 60), List.of(ASHA), null, FEEDBACK, NOW))
                .hasMessageContaining("no longer interviewing");
    }

    @Test
    void debriefIsReadableByCoordinatorsAndByInterviewersOnlyAfterTheyHaveSubmitted() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(
                round,
                slot(NOW.minus(Duration.ofHours(2)), 60),
                List.of(ASHA),
                null,
                FEEDBACK,
                NOW.minus(Duration.ofDays(1)));

        assertThat(loop.canReadDebrief(RECRUITER)).isTrue();
        assertThat(loop.canReadDebrief(ASHA_VIEW)).isFalse();
        assertThat(loop.canReadDebrief(OUTSIDER)).isFalse();
        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.GOOD, Rating.GOOD), "Fine", null, NOW);
        assertThat(loop.canReadDebrief(ASHA_VIEW)).isTrue();
    }

    @Test
    void eventsCarryIdentifiersButNoScores() {
        var loop = loop();
        var round = first(loop);
        loop.schedule(
                round,
                slot(NOW.minus(Duration.ofHours(2)), 60),
                List.of(ASHA),
                null,
                FEEDBACK,
                NOW.minus(Duration.ofDays(1)));
        loop.submitScorecard(round, ASHA_VIEW, ratings(Rating.POOR, Rating.GOOD), "Weak on design", null, NOW);

        var submitted = loop.pendingEvents().getLast();
        assertThat(submitted.eventType()).isEqualTo("interview.scorecard.submitted.v1");
        assertThat(submitted.payload())
                .containsKeys("loopId", "applicationId", "roundId")
                .doesNotContainKeys("ratings", "evidence");
        assertThat(submitted.payload().toString()).doesNotContain("POOR").doesNotContain("Weak on design");
    }
}
