package com.openhire.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.interview.domain.InterviewLoop;
import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.Rating;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.RoundPlan;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import com.openhire.interview.domain.SlotFinder;
import com.openhire.interview.domain.SlotFinder.Interval;
import com.openhire.interview.service.port.InterviewPorts.ApplicationDirectory;
import com.openhire.interview.service.port.InterviewPorts.ApplicationInfo;
import com.openhire.interview.service.port.InterviewPorts.BookingConflict;
import com.openhire.interview.service.port.InterviewPorts.BookingLedger;
import com.openhire.interview.service.port.InterviewPorts.Hold;
import com.openhire.interview.service.port.InterviewPorts.JobDirectory;
import com.openhire.interview.service.port.InterviewPorts.JobPlan;
import com.openhire.interview.service.port.InterviewPorts.LoopRepository;
import com.openhire.interview.service.port.InterviewPorts.SchedulingTokens;
import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import com.openhire.interview.service.port.InterviewPorts.TokenClaims;
import com.openhire.platform.audit.AuditRecorder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InterviewApplicationServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-12T02:30:00Z")); // Monday 08:00 IST
    private final MemoryLoops loops = new MemoryLoops();
    private final MemoryLedger ledger = new MemoryLedger();
    private final MemoryHolds holds = new MemoryHolds(clock);
    private final AuditProbe audit = new AuditProbe();
    private static final Viewer COORDINATOR = new Viewer("sub-r", "r@x.test", "Rita Recruiter", Set.of("RECRUITER"));
    private String stage = "INTERVIEWING";
    private InterviewApplicationService service;
    private ApplicationDirectory applications;
    private JobDirectory jobs;
    private SchedulingTokens tokens;
    private InterviewSettings settings;

    @BeforeEach
    void setUp() {
        applications =
                (tenant, id) -> new ApplicationInfo(id, "cand-1", "Priya Das", "job-1", "Backend Engineer", stage);
        jobs = (tenant, id) -> new JobPlan(
                id,
                "Backend Engineer",
                List.of(new RoundPlan("Technical", 60, List.of())),
                List.of(new Competency("c1", "Coding")));
        tokens = new SchedulingTokens() {
            public String issue(String tenantId, String roundId, Instant expiresAt) {
                return tenantId + "~" + roundId + "~" + expiresAt.getEpochSecond();
            }

            public TokenClaims verify(String token, Instant now) {
                var parts = token.split("~");
                return new TokenClaims(parts[0], parts[1], Instant.ofEpochSecond(Long.parseLong(parts[2])));
            }
        };
        settings = new InterviewSettings(
                Duration.ofHours(24),
                3,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                "http://portal.test",
                ZoneId.of("Asia/Kolkata"),
                SlotFinder.Policy.standard());
        service = serviceAuditedBy(audit.recorder);
    }

    private InterviewApplicationService serviceAuditedBy(AuditRecorder recorder) {
        return new InterviewApplicationService(
                loops, ledger, holds, applications, jobs, tokens, settings, clock, recorder);
    }

    private String plannedRound() {
        service.createLoop("t1", "app-1", "sub-r");
        var round = loops.findByApplication("t1", "app-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .id();
        service.schedule(
                "t1",
                round,
                clock.instant().minus(Duration.ofDays(20)),
                null,
                List.of(new PanelMember("Asha", "asha@x.test", null)),
                null,
                COORDINATOR);
        service.cancel("t1", round, "Replanning", COORDINATOR);
        return round;
    }

    private String token(String url) {
        return URLDecoder.decode(url.substring(url.indexOf("#token=") + 7), StandardCharsets.UTF_8);
    }

    @Test
    void loopsAreOnlyPlannedForApplicationsInTheInterviewStagesAndOnlyOnce() {
        stage = "APPLIED";
        assertThatThrownBy(() -> service.createLoop("t1", "app-1", "sub-r")).hasMessageContaining("Interviewing");
        stage = "INTERVIEWING";
        var first = service.createLoop("t1", "app-1", "sub-r");
        var second = service.createLoop("t1", "app-1", "sub-r");
        assertThat(second.loopId()).isEqualTo(first.loopId());
        assertThat(loops.saves).isEqualTo(1);
    }

    @Test
    void aCandidateBooksOneOfTheHeldTimesThroughTheLink() {
        var round = plannedRound();
        var link = service.schedulingLink("t1", round);

        assertThat(link.url()).startsWith("http://portal.test/schedule#token=");
        assertThat(link.slots()).hasSize(5);
        var view = service.publicView(token(link.url()));
        assertThat(view.slots()).isEqualTo(link.slots());

        var booked =
                service.publicBook(token(link.url()), Instant.parse(link.slots().get(1)));

        assertThat(booked.bookedStart()).isEqualTo(link.slots().get(1));
        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).status())
                .isEqualTo(RoundStatus.SCHEDULED);
        assertThat(holds.current("t1", round)).isEmpty();
        assertThat(ledger.blocksOf(round)).hasSize(4);
    }

    @Test
    void anExpiredHoldCannotBeBookedButReopeningTheLinkOffersFreshTimes() {
        var round = plannedRound();
        var link = service.schedulingLink("t1", round);
        clock.advance(Duration.ofMinutes(31));

        assertThatThrownBy(() -> service.publicBook(
                        token(link.url()), Instant.parse(link.slots().getFirst())))
                .hasMessageContaining("expired");
        var refreshed = service.publicView(token(link.url()));
        assertThat(refreshed.slots()).isNotEmpty();
        assertThat(Instant.parse(refreshed.holdExpiresAt())).isAfter(clock.instant());
    }

    @Test
    void onlyOfferedTimesCanBeBooked() {
        var round = plannedRound();
        var link = service.schedulingLink("t1", round);

        assertThatThrownBy(() -> service.publicBook(token(link.url()), Instant.parse("2026-10-30T05:00:00Z")))
                .hasMessageContaining("offered");
    }

    @Test
    void aBookingConflictLeavesTheRoundUnbookedAndReservesNothing() {
        var round = plannedRound();
        var link = service.schedulingLink("t1", round);
        var chosen = Instant.parse(link.slots().getFirst());
        ledger.reserve("t1", "other-round", List.of("email:asha@x.test"), chosen, chosen.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> service.publicBook(token(link.url()), chosen)).isInstanceOf(BookingConflict.class);
        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).status())
                .isEqualTo(RoundStatus.CANCELLED);
        assertThat(ledger.blocksOf(round)).isEmpty();
    }

    /** Books the last offered time through a fresh link and returns that link's token. */
    private String bookedThroughLink(String round) {
        var link = service.schedulingLink("t1", round);
        service.publicBook(token(link.url()), Instant.parse(link.slots().getLast()));
        return token(link.url());
    }

    @Test
    void aCandidateMovesTheirBookingToAnotherHeldTime() {
        var round = plannedRound();
        var token = bookedThroughLink(round);
        var booked = service.publicView(token);
        assertThat(booked.canChange()).isTrue();
        var movesBefore =
                loops.findByRound("t1", round).orElseThrow().round(round).rescheduleCount();

        var options = service.publicAlternatives(token);
        assertThat(options.bookedStart()).isEqualTo(booked.bookedStart());
        assertThat(options.slots()).isNotEmpty().doesNotContain(booked.bookedStart());

        var moved = service.publicBook(token, Instant.parse(options.slots().getFirst()));

        assertThat(moved.bookedStart()).isEqualTo(options.slots().getFirst());
        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).rescheduleCount())
                .isEqualTo(movesBefore + 1);
        assertThat(ledger.blocksOf(round)).allMatch(block -> !block.isBefore(Instant.parse(moved.bookedStart())));
        assertThat(holds.current("t1", round)).isEmpty();
    }

    @Test
    void aCandidateCancelsAndTheLinkThenShowsTheCancellationInsteadOfNewTimes() {
        var round = plannedRound();
        var token = bookedThroughLink(round);

        var cancelled = service.publicCancel(token, "Accepted another offer");

        assertThat(cancelled.status()).isEqualTo("CANCELLED_BY_CANDIDATE");
        assertThat(ledger.blocksOf(round)).isEmpty();
        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).cancelReason())
                .isEqualTo("Cancelled by the candidate: Accepted another offer");
        var reopened = service.publicView(token);
        assertThat(reopened.status()).isEqualTo("CANCELLED_BY_CANDIDATE");
        assertThat(reopened.slots()).isEmpty();
    }

    @Test
    void candidatesCannotChangeABookingInTheLastDay() {
        var round = plannedRound();
        var token = bookedThroughLink(round);
        var booked = service.publicView(token);
        clock.advance(Duration.between(clock.instant(), Instant.parse(booked.bookedStart()))
                .minusHours(23));

        var view = service.publicView(token);

        assertThat(view.canChange()).isFalse();
        assertThat(view.changeNote()).contains("24 hours");
        assertThatThrownBy(() -> service.publicAlternatives(token)).hasMessageContaining("24 hours");
        assertThatThrownBy(() -> service.publicCancel(token, null)).hasMessageContaining("24 hours");
    }

    @Test
    void reschedulingMovesTheReservedBlocks() {
        service.createLoop("t1", "app-1", "sub-r");
        var round = loops.findByApplication("t1", "app-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .id();
        var start = Instant.parse("2026-10-14T05:00:00Z");
        service.schedule(
                "t1", round, start, null, List.of(new PanelMember("Asha", "asha@x.test", null)), null, COORDINATOR);
        service.reschedule("t1", round, start.plus(Duration.ofHours(2)));

        assertThat(ledger.blocksOf(round)).allMatch(block -> !block.isBefore(start.plus(Duration.ofHours(2))));
        assertThat(ledger.blocksOf(round)).hasSize(4);
    }

    @Test
    void closingTheApplicationCancelsAndReleasesBookedRounds() {
        service.createLoop("t1", "app-1", "sub-r");
        var round = loops.findByApplication("t1", "app-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .id();
        service.schedule(
                "t1",
                round,
                Instant.parse("2026-10-14T05:00:00Z"),
                null,
                List.of(new PanelMember("Asha", "asha@x.test", null)),
                null,
                COORDINATOR);

        service.closeForApplication("t1", "app-1", "The candidate withdrew");

        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).status())
                .isEqualTo(RoundStatus.CANCELLED);
        assertThat(ledger.blocksOf(round)).isEmpty();
    }

    @Test
    void schedulingAndCancellingByACoordinatorAreAuditedWithoutPersonalData() {
        var round = plannedRound();

        var scheduled = audit.only("INTERVIEW_SCHEDULED");
        assertThat(scheduled)
                .contains("\"id\":\"sub-r\"", "\"type\":\"USER\"", "InterviewRound", round, "cand-1")
                .doesNotContain("asha@x.test", "Priya", "Asha");
        var cancelled = audit.only("INTERVIEW_CANCELLED");
        assertThat(cancelled)
                .contains("\"id\":\"sub-r\"", round, "COORDINATOR")
                .doesNotContain("Replanning", "asha@x.test", "Priya");
    }

    @Test
    void aCandidateBookingAndCancellingAreAuditedAsTheCandidate() {
        var round = plannedRound();
        var link = service.schedulingLink("t1", round);
        service.publicBook(token(link.url()), Instant.parse(link.slots().getLast()));
        service.publicCancel(token(link.url()), "Accepted another offer");

        assertThat(audit.records())
                .filteredOn(json -> json.contains("INTERVIEW_SCHEDULED"))
                .hasSize(2);
        assertThat(audit.records().getLast())
                .contains("INTERVIEW_CANCELLED", "\"type\":\"CANDIDATE\"", "\"id\":\"cand-1\"")
                .doesNotContain("Accepted another offer");
    }

    @Test
    void closingTheApplicationAuditsEachBookedRoundCancellationAsTheSystem() {
        service.createLoop("t1", "app-1", "sub-r");
        var round = loops.findByApplication("t1", "app-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .id();
        service.schedule(
                "t1",
                round,
                Instant.parse("2026-10-14T05:00:00Z"),
                null,
                List.of(new PanelMember("Asha", "asha@x.test", null)),
                null,
                COORDINATOR);

        service.closeForApplication("t1", "app-1", "The candidate withdrew");

        assertThat(audit.only("INTERVIEW_CANCELLED"))
                .contains("\"type\":\"SYSTEM\"", "APPLICATION_CLOSED")
                .doesNotContain("withdrew");
    }

    @Test
    void submittingAScorecardAuditsWhoScoredButNotWhatTheyWrote() {
        var round = plannedRound();
        service.schedule(
                "t1",
                round,
                clock.instant().minus(Duration.ofDays(1)),
                null,
                List.of(new PanelMember("Asha", "asha@x.test", null)),
                null,
                COORDINATOR);
        var asha = new Viewer("sub-asha", "asha@x.test", "Asha Rao", Set.of("INTERVIEWER"));

        service.submitScorecard(
                "t1", round, asha, List.of(new RatingEntry("c1", Rating.GOOD)), "Secret evidence text", null);

        assertThat(audit.only("SCORECARD_SUBMITTED"))
                .contains("\"id\":\"sub-asha\"", "InterviewRound", round, "cand-1")
                .doesNotContain("Secret evidence text", "GOOD", "asha@x.test");
    }

    @Test
    void anAuditFailureNeverBreaksTheInterviewAction() {
        var guarded = serviceAuditedBy(new AuditProbe().failing().recorder);
        guarded.createLoop("t1", "app-1", "sub-r");
        var round = loops.findByApplication("t1", "app-1")
                .orElseThrow()
                .rounds()
                .getFirst()
                .id();

        guarded.schedule(
                "t1",
                round,
                clock.instant().plus(Duration.ofDays(2)),
                null,
                List.of(new PanelMember("Asha", "asha@x.test", null)),
                null,
                COORDINATOR);
        guarded.cancel("t1", round, null, COORDINATOR);

        assertThat(loops.findByRound("t1", round).orElseThrow().round(round).status())
                .isEqualTo(RoundStatus.CANCELLED);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    static final class MemoryLoops implements LoopRepository {
        final Map<String, InterviewLoop> rows = new HashMap<>();
        int saves;

        public InterviewLoop save(InterviewLoop loop) {
            saves++;
            rows.put(loop.id(), loop);
            return loop;
        }

        public Optional<InterviewLoop> findByApplication(String tenantId, String applicationId) {
            return rows.values().stream()
                    .filter(l ->
                            l.tenantId().equals(tenantId) && l.applicationId().equals(applicationId))
                    .findFirst();
        }

        public Optional<InterviewLoop> findByRound(String tenantId, String roundId) {
            return rows.values().stream()
                    .filter(l ->
                            l.tenantId().equals(tenantId) && l.rounds().stream().anyMatch(r -> r.id().equals(roundId)))
                    .findFirst();
        }

        public List<InterviewLoop> findWithRoundsStartingBetween(String tenantId, Instant from, Instant to) {
            return List.copyOf(rows.values());
        }

        public List<InterviewLoop> findWithUnscheduledRounds(String tenantId) {
            return List.copyOf(rows.values());
        }

        public List<InterviewLoop> findForInterviewer(String tenantId, String email, String name) {
            return List.copyOf(rows.values());
        }

        public List<InterviewLoop> findNeedingSweep(Instant now, int limit) {
            return List.copyOf(rows.values());
        }
    }

    static final class MemoryLedger implements BookingLedger {
        record Block(String tenant, String key, Instant start, String round) {}

        final List<Block> blocks = new ArrayList<>();

        public void reserve(String tenantId, String roundId, Collection<String> keys, Instant start, Instant end) {
            var added = new ArrayList<Block>();
            for (var key : keys)
                for (var block : SlotFinder.blocks(start, end)) {
                    var existing = blocks.stream()
                            .filter(b -> b.tenant().equals(tenantId)
                                    && b.key().equals(key)
                                    && b.start().equals(block))
                            .findFirst();
                    if (existing.isPresent()) {
                        if (existing.get().round().equals(roundId)) continue;
                        blocks.removeAll(added);
                        throw new BookingConflict(key + " is already booked at that time");
                    }
                    var value = new Block(tenantId, key, block, roundId);
                    blocks.add(value);
                    added.add(value);
                }
        }

        public void release(String tenantId, String roundId) {
            blocks.removeIf(b -> b.tenant().equals(tenantId) && b.round().equals(roundId));
        }

        public void retain(String tenantId, String roundId, Collection<String> keys, Instant start, Instant end) {
            var keep = SlotFinder.blocks(start, end);
            blocks.removeIf(b -> b.tenant().equals(tenantId)
                    && b.round().equals(roundId)
                    && !(keys.contains(b.key()) && keep.contains(b.start())));
        }

        public List<Interval> busy(String tenantId, Collection<String> keys, Instant from, Instant to) {
            return blocks.stream()
                    .filter(b -> b.tenant().equals(tenantId) && keys.contains(b.key()))
                    .map(b -> new Interval(b.start(), b.start().plus(SlotFinder.BLOCK)))
                    .toList();
        }

        List<Instant> blocksOf(String roundId) {
            return blocks.stream()
                    .filter(b -> b.round().equals(roundId))
                    .map(Block::start)
                    .toList();
        }
    }

    static final class MemoryHolds implements SlotHolds {
        private final Clock clock;
        private final Map<String, Hold> rows = new HashMap<>();

        MemoryHolds(Clock clock) {
            this.clock = clock;
        }

        public Hold hold(
                String tenantId,
                String roundId,
                Collection<String> keys,
                List<Instant> slots,
                Duration length,
                Duration ttl) {
            var hold = new Hold(slots, clock.instant().plus(ttl));
            rows.put(tenantId + roundId, hold);
            return hold;
        }

        public Optional<Hold> current(String tenantId, String roundId) {
            return Optional.ofNullable(rows.get(tenantId + roundId))
                    .filter(h -> h.expiresAt().isAfter(clock.instant()));
        }

        public List<Interval> heldByOtherRounds(String tenantId, Collection<String> keys, String roundId) {
            return List.of();
        }

        public void clear(String tenantId, String roundId, Collection<String> keys) {
            rows.remove(tenantId + roundId);
        }
    }
}
