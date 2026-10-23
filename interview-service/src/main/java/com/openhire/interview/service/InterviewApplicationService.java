package com.openhire.interview.service;

import com.openhire.interview.domain.DebriefBuilder;
import com.openhire.interview.domain.InterviewLoop;
import com.openhire.interview.domain.InterviewRound;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.Recommendation;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import com.openhire.interview.domain.InterviewTypes.Scorecard;
import com.openhire.interview.domain.InterviewTypes.Slot;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import com.openhire.interview.domain.SlotFinder;
import com.openhire.interview.dto.InterviewViews.CompetencyView;
import com.openhire.interview.dto.InterviewViews.InterviewEventView;
import com.openhire.interview.dto.InterviewViews.LoopView;
import com.openhire.interview.dto.InterviewViews.MyInterviewView;
import com.openhire.interview.dto.InterviewViews.Page;
import com.openhire.interview.dto.InterviewViews.PanelView;
import com.openhire.interview.dto.InterviewViews.PublicSchedulingView;
import com.openhire.interview.dto.InterviewViews.RoundView;
import com.openhire.interview.dto.InterviewViews.SchedulingLinkView;
import com.openhire.interview.dto.InterviewViews.ScorecardView;
import com.openhire.interview.dto.InterviewViews.SubmittedScorecardView;
import com.openhire.interview.dto.InterviewViews.UnscheduledRoundView;
import com.openhire.interview.service.port.InterviewPorts.ApplicationDirectory;
import com.openhire.interview.service.port.InterviewPorts.BookingLedger;
import com.openhire.interview.service.port.InterviewPorts.Hold;
import com.openhire.interview.service.port.InterviewPorts.JobDirectory;
import com.openhire.interview.service.port.InterviewPorts.LoopRepository;
import com.openhire.interview.service.port.InterviewPorts.SchedulingTokens;
import com.openhire.interview.service.port.InterviewPorts.SlotHolds;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class InterviewApplicationService {
    /** Candidates may move or cancel a booking themselves until this long before it starts. */
    private static final Duration CANDIDATE_NOTICE = Duration.ofHours(24);

    /** The viewer may not see or change this resource. */
    public static final class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    private static final Set<String> SCHEDULABLE_STAGES = Set.of("SHORTLISTED", "INTERVIEWING", "DECISION_PENDING");

    private final LoopRepository loops;
    private final BookingLedger ledger;
    private final SlotHolds holds;
    private final ApplicationDirectory applications;
    private final JobDirectory jobs;
    private final SchedulingTokens tokens;
    private final InterviewSettings settings;
    private final Clock clock;
    private final AuditRecorder audit;

    public InterviewApplicationService(
            LoopRepository loops,
            BookingLedger ledger,
            SlotHolds holds,
            ApplicationDirectory applications,
            JobDirectory jobs,
            SchedulingTokens tokens,
            InterviewSettings settings,
            Clock clock,
            AuditRecorder audit) {
        this.loops = loops;
        this.ledger = ledger;
        this.holds = holds;
        this.applications = applications;
        this.jobs = jobs;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the service can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    public LoopView createLoop(String tenantId, String applicationId, String ownerId) {
        var existing = loops.findByApplication(tenantId, applicationId);
        if (existing.isPresent()) return loopView(existing.get());
        var application = applications.application(tenantId, applicationId);
        if (tombstones != null && tombstones.isErased(tenantId, application.candidateId()))
            throw new IllegalStateException("The candidate's data was erased; no interview loop is planned");
        if (!SCHEDULABLE_STAGES.contains(application.stage()))
            throw new IllegalStateException("Move the application to Interviewing before planning interviews");
        var plan = jobs.interviewPlan(tenantId, application.jobId());
        var loop = InterviewLoop.create(
                tenantId,
                applicationId,
                application.candidateId(),
                application.candidateName(),
                application.jobId(),
                plan.title() == null || plan.title().isBlank() ? application.jobTitle() : plan.title(),
                ownerId,
                plan.rounds(),
                plan.competencies(),
                clock.instant());
        try {
            return loopView(loops.save(loop));
        } catch (DuplicateKeyException raced) {
            return loopView(loops.findByApplication(tenantId, applicationId).orElseThrow());
        }
    }

    /** Called when the application is rejected or withdrawn: cancels and releases rounds not yet held. */
    public void closeForApplication(String tenantId, String applicationId, String reason) {
        loops.findByApplication(tenantId, applicationId).ifPresent(loop -> {
            var open = loop.rounds().stream()
                    .filter(round -> Set.of(RoundStatus.SCHEDULED, RoundStatus.UNSCHEDULED)
                            .contains(round.status()))
                    .toList();
            var booked = open.stream()
                    .filter(round -> round.status() == RoundStatus.SCHEDULED)
                    .map(InterviewRound::id)
                    .collect(Collectors.toSet());
            if (loop.close(reason, clock.instant())) {
                loops.save(loop);
                open.forEach(round -> {
                    ledger.release(tenantId, round.id());
                    holds.clear(tenantId, round.id(), keys(round.panel()));
                    if (booked.contains(round.id()))
                        auditCancelled(loop, round.id(), AuditRecord.Builder::system, "APPLICATION_CLOSED");
                });
            }
        });
    }

    public LoopView loop(String tenantId, String applicationId) {
        return loopView(requireByApplication(tenantId, applicationId));
    }

    public Page<InterviewEventView> week(String tenantId, LocalDate anyDay, Viewer viewer) {
        var zone = settings.defaultZone();
        var monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var from = monday.atStartOfDay(zone).toInstant();
        var to = monday.plusWeeks(1).atStartOfDay(zone).toInstant();
        var result = new ArrayList<InterviewEventView>();
        for (var loop : loops.findWithRoundsStartingBetween(tenantId, from, to)) {
            for (var round : loop.rounds()) {
                if (round.slot() == null || round.status() == RoundStatus.UNSCHEDULED) continue;
                var start = round.slot().start();
                if (start.isBefore(from) || !start.isBefore(to)) continue;
                if (!viewer.coordinator() && !round.onPanel(viewer)) continue;
                result.add(eventView(loop, round, viewer));
            }
        }
        result.sort(Comparator.comparing(InterviewEventView::start));
        return Page.of(result);
    }

    public Page<UnscheduledRoundView> unscheduled(String tenantId) {
        var result = new ArrayList<UnscheduledRoundView>();
        for (var loop : loops.findWithUnscheduledRounds(tenantId)) {
            if (loop.closed()) continue;
            for (var round : loop.rounds()) {
                if (!Set.of(RoundStatus.UNSCHEDULED, RoundStatus.NO_SHOW).contains(round.status())) continue;
                result.add(new UnscheduledRoundView(
                        round.id(),
                        loop.id(),
                        loop.applicationId(),
                        loop.candidateName(),
                        loop.jobTitle(),
                        round.name(),
                        round.durationMin(),
                        panelViews(round.panel()),
                        round.status().name()));
            }
        }
        return Page.of(result);
    }

    public RoundView schedule(
            String tenantId,
            String roundId,
            Instant start,
            String timezone,
            List<PanelMember> panel,
            String meetingUrl,
            Viewer actor) {
        return schedule(
                tenantId, roundId, start, timezone, panel, meetingUrl, by -> by.user(actor.subject(), actor.name()));
    }

    private RoundView schedule(
            String tenantId,
            String roundId,
            Instant start,
            String timezone,
            List<PanelMember> panel,
            String meetingUrl,
            UnaryOperator<AuditRecord.Builder> by) {
        var loop = requireByRound(tenantId, roundId);
        var round = loop.round(roundId);
        var now = clock.instant();
        if (start.isBefore(now.minus(Duration.ofDays(30))) || start.isAfter(now.plus(Duration.ofDays(180))))
            throw new IllegalArgumentException("Choose a time within the last 30 days or the next 180 days");
        var members = panel == null || panel.isEmpty() ? round.panel() : panel;
        var slot = new Slot(start, start.plus(Duration.ofMinutes(round.durationMin())), zoneOr(timezone));
        book(
                tenantId,
                loop,
                round,
                members,
                slot,
                () -> loop.schedule(roundId, slot, members, blankToNull(meetingUrl), settings.feedbackWindow(), now));
        audit.record(by.apply(AuditRecord.of(tenantId, "INTERVIEW_SCHEDULED"))
                .resource("InterviewRound", roundId)
                .about(loop.candidateId(), loop.applicationId())
                .detail("panelSize", members.size())
                .build());
        return roundView(loop.round(roundId));
    }

    public RoundView assignPanel(String tenantId, String roundId, List<PanelMember> panel) {
        var loop = requireByRound(tenantId, roundId);
        loop.assignPanel(roundId, panel, clock.instant());
        loops.save(loop);
        return roundView(loop.round(roundId));
    }

    public void reschedule(String tenantId, String roundId, Instant start) {
        var loop = requireByRound(tenantId, roundId);
        var round = loop.round(roundId);
        var now = clock.instant();
        var slot = new Slot(
                start,
                start.plus(Duration.ofMinutes(round.durationMin())),
                round.slot() == null ? zoneOr(null) : round.slot().timezone());
        book(
                tenantId,
                loop,
                round,
                round.panel(),
                slot,
                () -> loop.reschedule(roundId, slot, settings.rescheduleLimit(), settings.feedbackWindow(), now));
    }

    public void cancel(String tenantId, String roundId, String reason, Viewer actor) {
        var loop = requireByRound(tenantId, roundId);
        loop.cancel(roundId, reason, clock.instant());
        loops.save(loop);
        ledger.release(tenantId, roundId);
        auditCancelled(loop, roundId, by -> by.user(actor.subject(), actor.name()), "COORDINATOR");
    }

    public void noShow(String tenantId, String roundId) {
        change(tenantId, roundId, loop -> loop.markNoShow(roundId, clock.instant()));
        ledger.release(tenantId, roundId);
    }

    public void complete(String tenantId, String roundId) {
        change(tenantId, roundId, loop -> loop.complete(roundId, clock.instant()));
    }

    public SchedulingLinkView schedulingLink(String tenantId, String roundId) {
        var loop = requireByRound(tenantId, roundId);
        var round = loop.round(roundId);
        if (round.panel().isEmpty())
            throw new IllegalStateException("Assign interviewers before sending a scheduling link");
        var now = clock.instant();
        var expires = now.plus(settings.linkValidity());
        var hold = proposeAndHold(tenantId, round);
        // The token sits in the fragment so browsers never send it to the portal server or its logs.
        var url = settings.portalUrl() + "/schedule#token="
                + URLEncoder.encode(tokens.issue(tenantId, roundId, expires), StandardCharsets.UTF_8);
        loop.recordSchedulingLink(roundId, url, expires, now);
        loops.save(loop);
        return new SchedulingLinkView(
                url, expires.toString(), hold.stream().map(Instant::toString).toList());
    }

    public PublicSchedulingView publicView(String token) {
        var claims = tokens.verify(token, clock.instant());
        var loop = requireByRound(claims.tenantId(), claims.roundId());
        var round = loop.round(claims.roundId());
        if (round.status() == RoundStatus.SCHEDULED || InterviewLoop.cancelledByCandidate(round))
            return publicSchedulingView(loop, round, List.of(), null);
        if (loop.closed()
                || !Set.of(RoundStatus.UNSCHEDULED, RoundStatus.CANCELLED).contains(round.status()))
            throw new IllegalStateException("This interview no longer needs booking");
        var hold = currentOrNewHold(claims.tenantId(), round);
        return publicSchedulingView(loop, round, hold.slots(), hold.expiresAt().toString());
    }

    /** Offers new times for a booked round; the current booking stands until the candidate picks one. */
    public PublicSchedulingView publicAlternatives(String token) {
        var claims = tokens.verify(token, clock.instant());
        var loop = requireByRound(claims.tenantId(), claims.roundId());
        var round = loop.round(claims.roundId());
        requireCandidateChange(loop, round, true);
        var hold = currentOrNewHold(claims.tenantId(), round);
        return publicSchedulingView(loop, round, hold.slots(), hold.expiresAt().toString());
    }

    public PublicSchedulingView publicBook(String token, Instant start) {
        var claims = tokens.verify(token, clock.instant());
        var loop = requireByRound(claims.tenantId(), claims.roundId());
        var round = loop.round(claims.roundId());
        var hold = holds.current(claims.tenantId(), round.id())
                .orElseThrow(
                        () -> new IllegalStateException("These times have expired. Reload the page to see new times."));
        if (!hold.slots().contains(start)) throw new IllegalArgumentException("Choose one of the offered times");
        if (round.status() == RoundStatus.SCHEDULED) {
            requireCandidateChange(loop, round, true);
            reschedule(claims.tenantId(), round.id(), start);
        } else {
            schedule(
                    claims.tenantId(),
                    round.id(),
                    start,
                    null,
                    round.panel(),
                    null,
                    by -> by.candidate(loop.candidateId()));
        }
        holds.clear(claims.tenantId(), round.id(), keys(round.panel()));
        var updated = requireByRound(claims.tenantId(), round.id());
        return publicSchedulingView(updated, updated.round(round.id()), List.of(), null);
    }

    public PublicSchedulingView publicCancel(String token, String reason) {
        var claims = tokens.verify(token, clock.instant());
        var loop = requireByRound(claims.tenantId(), claims.roundId());
        var round = loop.round(claims.roundId());
        requireCandidateChange(loop, round, false);
        loop.cancelByCandidate(round.id(), reason, clock.instant());
        loops.save(loop);
        ledger.release(claims.tenantId(), round.id());
        holds.clear(claims.tenantId(), round.id(), keys(round.panel()));
        auditCancelled(loop, round.id(), by -> by.candidate(loop.candidateId()), "CANDIDATE");
        return publicSchedulingView(loop, loop.round(round.id()), List.of(), null);
    }

    private Hold currentOrNewHold(String tenantId, InterviewRound round) {
        return holds.current(tenantId, round.id()).orElseGet(() -> {
            proposeAndHold(tenantId, round);
            return holds.current(tenantId, round.id()).orElseThrow();
        });
    }

    private void requireCandidateChange(InterviewLoop loop, InterviewRound round, boolean reschedule) {
        var refusal = candidateChangeRefusal(loop, round, reschedule);
        if (refusal != null) throw new IllegalStateException(refusal);
    }

    /** Why the candidate cannot change this booking themselves, or null when they can. */
    private String candidateChangeRefusal(InterviewLoop loop, InterviewRound round, boolean reschedule) {
        if (loop.closed() || round.status() != RoundStatus.SCHEDULED) return "This interview is not booked";
        if (!round.slot().start().isAfter(clock.instant().plus(CANDIDATE_NOTICE)))
            return "Changes are not possible within " + CANDIDATE_NOTICE.toHours()
                    + " hours of the interview. Reply to your confirmation email instead.";
        if (reschedule && round.rescheduleCount() >= settings.rescheduleLimit())
            return "This interview has already been moved " + settings.rescheduleLimit()
                    + " times. Reply to your confirmation email to change it.";
        return null;
    }

    public ScorecardView myScorecard(String tenantId, String roundId, Viewer viewer) {
        var loop = requireByRound(tenantId, roundId);
        var round = loop.round(roundId);
        var member = round.panelMember(viewer);
        if (member == null) throw new AccessDenied("You are not on this round's panel");
        var own = round.scorecardOf(member.key());
        var competencies = round.competencies().stream()
                .map(value ->
                        new CompetencyView(value.id(), value.label(), own == null ? null : ratingOf(own, value.id())))
                .toList();
        return new ScorecardView(
                roundId,
                loop.candidateName(),
                round.name(),
                competencies,
                own == null ? "" : own.evidence(),
                own != null,
                round.kitQuestions());
    }

    public void submitScorecard(
            String tenantId,
            String roundId,
            Viewer viewer,
            List<RatingEntry> ratings,
            String evidence,
            Recommendation recommendation) {
        var loop = requireByRound(tenantId, roundId);
        loop.submitScorecard(roundId, viewer, ratings, evidence, recommendation, clock.instant());
        loops.save(loop);
        // Who scored is recorded, never what they wrote or rated.
        audit.record(AuditRecord.of(tenantId, "SCORECARD_SUBMITTED")
                .user(viewer.subject(), viewer.name())
                .resource("InterviewRound", roundId)
                .about(loop.candidateId(), loop.applicationId())
                .detail("ratingCount", ratings == null ? 0 : ratings.size())
                .build());
    }

    public List<SubmittedScorecardView> scorecards(String tenantId, String roundId, Viewer viewer) {
        var loop = requireByRound(tenantId, roundId);
        var round = loop.round(roundId);
        try {
            return loop.visibleScorecards(roundId, viewer).stream()
                    .map(card -> new SubmittedScorecardView(
                            card.interviewerName(),
                            round.competencies().stream()
                                    .map(value ->
                                            new CompetencyView(value.id(), value.label(), ratingOf(card, value.id())))
                                    .toList(),
                            card.evidence(),
                            card.recommendation() == null
                                    ? null
                                    : card.recommendation().name(),
                            card.submittedAt().toString()))
                    .toList();
        } catch (IllegalStateException hidden) {
            throw new AccessDenied(hidden.getMessage());
        }
    }

    public DebriefBuilder.Debrief debrief(String tenantId, String applicationId, Viewer viewer) {
        var loop = requireByApplication(tenantId, applicationId);
        if (!loop.canReadDebrief(viewer))
            throw new AccessDenied("The debrief opens to interviewers after they submit their scorecards");
        return DebriefBuilder.build(loop);
    }

    public Page<MyInterviewView> myInterviews(String tenantId, Viewer viewer) {
        var now = clock.instant();
        var result = new ArrayList<MyInterviewView>();
        for (var loop : loops.findForInterviewer(tenantId, viewer.email(), viewer.name())) {
            for (var round : loop.rounds()) {
                var member = round.panelMember(viewer);
                if (member == null || round.status() == RoundStatus.CANCELLED) continue;
                String mine;
                if (round.scorecardOf(member.key()) != null) mine = "SUBMITTED";
                else if (round.status() == RoundStatus.FEEDBACK_OVERDUE) mine = "OVERDUE";
                else if (round.status() == RoundStatus.COMPLETED
                        || (round.slot() != null
                                && round.status() == RoundStatus.SCHEDULED
                                && !round.slot().start().isAfter(now))) mine = "DUE";
                else mine = "UPCOMING";
                result.add(new MyInterviewView(
                        round.id(),
                        loop.applicationId(),
                        loop.candidateName(),
                        loop.jobTitle(),
                        round.name(),
                        round.slot() == null ? null : round.slot().start().toString(),
                        round.durationMin(),
                        round.status().name(),
                        mine,
                        round.feedbackDueAt() == null
                                ? null
                                : round.feedbackDueAt().toString()));
            }
        }
        result.sort(Comparator.comparing(MyInterviewView::start, Comparator.nullsLast(Comparator.naturalOrder())));
        return Page.of(result);
    }

    /** Applies time-driven transitions; returns how many loops changed. Safe to run on any instance under a lock. */
    public int sweep() {
        var now = clock.instant();
        int changed = 0;
        for (var loop : loops.findNeedingSweep(now, 200)) {
            if (!loop.sweep(now)) continue;
            try {
                loops.save(loop);
                changed++;
            } catch (org.springframework.dao.OptimisticLockingFailureException concurrent) {
                // A user changed the loop meanwhile; the next sweep re-evaluates it.
            }
        }
        return changed;
    }

    private List<Instant> proposeAndHold(String tenantId, InterviewRound round) {
        var now = clock.instant();
        var zone = round.slot() == null
                ? settings.defaultZone()
                : ZoneId.of(round.slot().timezone());
        var horizon = now.plus(Duration.ofDays(settings.policy().horizonDays() + 1L));
        var keys = keys(round.panel());
        var busy = new ArrayList<>(ledger.busy(tenantId, keys, now, horizon));
        busy.addAll(holds.heldByOtherRounds(tenantId, keys, round.id()));
        var slots = SlotFinder.propose(now, zone, Duration.ofMinutes(round.durationMin()), busy, settings.policy());
        if (slots.isEmpty())
            throw new IllegalStateException("No shared free time for this panel in the next two weeks");
        return holds.hold(
                        tenantId,
                        round.id(),
                        keys,
                        slots,
                        Duration.ofMinutes(round.durationMin()),
                        settings.holdDuration())
                .slots();
    }

    private void book(
            String tenantId,
            InterviewLoop loop,
            InterviewRound round,
            List<PanelMember> members,
            Slot slot,
            Runnable command) {
        var previous = round.slot();
        var previousKeys = keys(round.panel());
        boolean wasBooked = round.status() == RoundStatus.SCHEDULED;
        ledger.reserve(tenantId, round.id(), keys(members), slot.start(), slot.end());
        try {
            command.run();
            loops.save(loop);
        } catch (RuntimeException failed) {
            if (wasBooked) ledger.retain(tenantId, round.id(), previousKeys, previous.start(), previous.end());
            else ledger.release(tenantId, round.id());
            throw failed;
        }
        // Drop blocks from the previous booking that the new one no longer covers.
        ledger.retain(tenantId, round.id(), keys(members), slot.start(), slot.end());
    }

    private void auditCancelled(
            InterviewLoop loop, String roundId, UnaryOperator<AuditRecord.Builder> by, String cancelledBy) {
        audit.record(by.apply(AuditRecord.of(loop.tenantId(), "INTERVIEW_CANCELLED"))
                .resource("InterviewRound", roundId)
                .about(loop.candidateId(), loop.applicationId())
                .detail("cancelledBy", cancelledBy)
                .build());
    }

    private void change(String tenantId, String roundId, Consumer<InterviewLoop> command) {
        var loop = requireByRound(tenantId, roundId);
        command.accept(loop);
        loops.save(loop);
    }

    private InterviewLoop requireByRound(String tenantId, String roundId) {
        return loops.findByRound(tenantId, roundId)
                .orElseThrow(() -> new ResourceNotFoundException("Round " + roundId + " was not found"));
    }

    private InterviewLoop requireByApplication(String tenantId, String applicationId) {
        return loops.findByApplication(tenantId, applicationId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("No interviews are planned for application " + applicationId));
    }

    private InterviewEventView eventView(InterviewLoop loop, InterviewRound round, Viewer viewer) {
        var member = round.panelMember(viewer);
        boolean submitted = member != null
                ? round.scorecardOf(member.key()) != null
                : !round.panel().isEmpty() && round.missingInterviewers().isEmpty();
        var status =
                switch (round.status()) {
                    case COMPLETED, FEEDBACK_OVERDUE -> "COMPLETED";
                    default -> round.status().name();
                };
        return new InterviewEventView(
                round.id(),
                loop.applicationId(),
                loop.candidateId(),
                loop.candidateName(),
                loop.jobTitle(),
                round.name(),
                round.slot().start().toString(),
                round.durationMin(),
                String.join(", ", round.panel().stream().map(PanelMember::name).toList()),
                status,
                submitted);
    }

    private LoopView loopView(InterviewLoop loop) {
        return new LoopView(
                loop.id(),
                loop.applicationId(),
                loop.candidateName(),
                loop.jobTitle(),
                loop.closed(),
                loop.rounds().stream().map(this::roundView).toList());
    }

    private RoundView roundView(InterviewRound round) {
        return new RoundView(
                round.id(),
                round.seq(),
                round.name(),
                round.durationMin(),
                round.status().name(),
                round.slot() == null ? null : round.slot().start().toString(),
                round.slot() == null ? null : round.slot().end().toString(),
                round.slot() == null ? null : round.slot().timezone(),
                panelViews(round.panel()),
                round.meetingUrl(),
                round.rescheduleCount(),
                round.feedbackDueAt() == null ? null : round.feedbackDueAt().toString(),
                round.scorecards().size(),
                round.panel().size());
    }

    private PublicSchedulingView publicSchedulingView(
            InterviewLoop loop, InterviewRound round, List<Instant> slots, String holdExpiresAt) {
        var booked = round.status() == RoundStatus.SCHEDULED;
        var cancelRefusal = booked ? candidateChangeRefusal(loop, round, false) : null;
        var moveRefusal = booked ? candidateChangeRefusal(loop, round, true) : null;
        return new PublicSchedulingView(
                loop.jobTitle(),
                round.name(),
                round.durationMin(),
                round.slot() == null
                        ? settings.defaultZone().getId()
                        : round.slot().timezone(),
                slots.stream().map(Instant::toString).toList(),
                holdExpiresAt,
                booked ? round.slot().start().toString() : null,
                InterviewLoop.cancelledByCandidate(round)
                        ? "CANCELLED_BY_CANDIDATE"
                        : round.status().name(),
                booked && cancelRefusal == null,
                moveRefusal != null ? moveRefusal : cancelRefusal);
    }

    private static List<PanelView> panelViews(List<PanelMember> panel) {
        return panel.stream()
                .map(member -> new PanelView(member.name(), member.email(), member.role()))
                .toList();
    }

    private static List<String> keys(List<PanelMember> panel) {
        return panel.stream().map(PanelMember::key).toList();
    }

    private static String ratingOf(Scorecard card, String competencyId) {
        return card.ratings().stream()
                .filter(entry -> entry.competencyId().equals(competencyId))
                .findFirst()
                .map(entry -> entry.rating().name())
                .orElse(null);
    }

    private String zoneOr(String timezone) {
        return timezone == null || timezone.isBlank()
                ? settings.defaultZone().getId()
                : ZoneId.of(timezone).getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
