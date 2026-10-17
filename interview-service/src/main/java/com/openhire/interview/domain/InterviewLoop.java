package com.openhire.interview.domain;

import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.PendingEvent;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.Recommendation;
import com.openhire.interview.domain.InterviewTypes.RoundPlan;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import com.openhire.interview.domain.InterviewTypes.Scorecard;
import com.openhire.interview.domain.InterviewTypes.Slot;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import com.openhire.platform.web.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Aggregate root: every interview round for one application, with its blind scorecards. */
@Document("interview_loops")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_application", def = "{'tenantId': 1, 'applicationId': 1}", unique = true),
    @CompoundIndex(name = "tenant_round_start", def = "{'tenantId': 1, 'rounds.slot.start': 1}"),
    @CompoundIndex(name = "round_sweep", def = "{'rounds.status': 1, 'rounds.slot.end': 1}"),
    @CompoundIndex(name = "round_id", def = "{'tenantId': 1, 'rounds.roundId': 1}")
})
public class InterviewLoop {
    public static final String PRODUCER = "interview-service";
    private static final String CANDIDATE_CANCELLED = "Cancelled by the candidate";

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String applicationId;
    private String candidateId;
    private String candidateName;
    private String jobId;
    private String jobTitle;
    private String ownerId;
    private boolean closed;
    private List<InterviewRound> rounds;
    private Instant createdAt;
    private Instant updatedAt;

    @Field("_events")
    private List<PendingEvent> events = new ArrayList<>();

    protected InterviewLoop() {}

    public static String idFor(String tenantId, String applicationId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000" + applicationId).getBytes(StandardCharsets.UTF_8));
            return "loop_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static InterviewLoop create(
            String tenantId,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String ownerId,
            List<RoundPlan> plan,
            List<Competency> competencies,
            Instant now) {
        if (plan.isEmpty()) throw new IllegalArgumentException("The job has no interview plan");
        var loop = new InterviewLoop();
        loop.id = idFor(tenantId, applicationId);
        loop.tenantId = tenantId;
        loop.applicationId = applicationId;
        loop.candidateId = candidateId;
        loop.candidateName = candidateName;
        loop.jobId = jobId;
        loop.jobTitle = jobTitle;
        loop.ownerId = ownerId;
        loop.rounds = new ArrayList<>();
        var rubric = competencies.isEmpty() ? List.of(new Competency("overall", "Overall assessment")) : competencies;
        for (int i = 0; i < plan.size(); i++) {
            var step = plan.get(i);
            var panel = step.interviewerNames().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> new PanelMember(name, null, "LEAD"))
                    .toList();
            loop.rounds.add(new InterviewRound(
                    loop.id + "_r" + (i + 1), i + 1, step.name(), step.durationMin(), panel, rubric));
        }
        loop.createdAt = now;
        loop.updatedAt = now;
        loop.record("interview.loop-created.v1", null, Map.of("roundCount", plan.size()), now);
        return loop;
    }

    public InterviewRound round(String roundId) {
        return rounds.stream()
                .filter(value -> value.id().equals(roundId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Round " + roundId + " was not found"));
    }

    public void schedule(
            String roundId,
            Slot slot,
            List<PanelMember> panel,
            String meetingUrl,
            Duration feedbackWindow,
            Instant now) {
        requireOpen();
        var round = round(roundId);
        // Booking after a cancellation or no-show is a new booking; moving a live booking goes through reschedule.
        round.schedule(slot, panel, meetingUrl, feedbackWindow);
        record("interview.scheduled.v1", round, Map.of(), now);
    }

    /** Sets who will interview before a time is booked, so a scheduling link can find shared free time. */
    public void assignPanel(String roundId, List<PanelMember> panel, Instant now) {
        requireOpen();
        round(roundId).assignPanel(panel);
        updatedAt = now;
    }

    public void reschedule(String roundId, Slot slot, int limit, Duration feedbackWindow, Instant now) {
        requireOpen();
        var round = round(roundId);
        var previous = round.slot().start().toString();
        round.reschedule(slot, now, limit, feedbackWindow);
        record("interview.rescheduled.v1", round, Map.of("previousStart", previous), now);
    }

    public void cancel(String roundId, String reason, Instant now) {
        var round = round(roundId);
        boolean wasScheduled = round.status() == RoundStatus.SCHEDULED;
        round.cancel(reason == null || reason.isBlank() ? "Cancelled by the hiring team" : reason.trim());
        record("interview.cancelled.v1", round, Map.of("notifyCandidate", wasScheduled, "cancelledBy", "STAFF"), now);
    }

    /** The candidate called it off; the hiring team is told, and the candidate already saw the confirmation. */
    public void cancelByCandidate(String roundId, String reason, Instant now) {
        requireOpen();
        var round = round(roundId);
        if (round.status() != RoundStatus.SCHEDULED) throw new IllegalStateException("This interview is not booked");
        var why = reason == null || reason.isBlank() ? "" : ": " + reason.trim();
        round.cancel(CANDIDATE_CANCELLED + why);
        var extra = new LinkedHashMap<String, Object>();
        extra.put("notifyCandidate", false);
        extra.put("cancelledBy", "CANDIDATE");
        extra.put("reason", round.cancelReason());
        record("interview.cancelled.v1", round, extra, now);
    }

    public static boolean cancelledByCandidate(InterviewRound round) {
        return round.status() == RoundStatus.CANCELLED
                && round.cancelReason() != null
                && round.cancelReason().startsWith(CANDIDATE_CANCELLED);
    }

    public void markNoShow(String roundId, Instant now) {
        var round = round(roundId);
        round.markNoShow(now);
        record("interview.no-show.v1", round, Map.of(), now);
    }

    public void complete(String roundId, Instant now) {
        var round = round(roundId);
        round.complete(now);
        record("interview.completed.v1", round, Map.of(), now);
    }

    public void submitScorecard(
            String roundId,
            Viewer viewer,
            List<RatingEntry> ratings,
            String evidence,
            Recommendation recommendation,
            Instant now) {
        var round = round(roundId);
        var before = round.status();
        round.submit(viewer, ratings, evidence, recommendation, now);
        if (before == RoundStatus.SCHEDULED) record("interview.completed.v1", round, Map.of(), now);
        record(
                "interview.scorecard.submitted.v1",
                round,
                Map.of(
                        "interviewerName",
                        round.panelMember(viewer).name(),
                        "remaining",
                        round.missingInterviewers().size()),
                now);
    }

    /** Time-driven transitions: rounds that ended become COMPLETED, late feedback becomes FEEDBACK_OVERDUE. */
    public boolean sweep(Instant now) {
        boolean changed = false;
        for (var round : rounds) {
            if (round.completeIfEnded(now)) {
                record("interview.completed.v1", round, Map.of(), now);
                changed = true;
            }
            if (round.markOverdueIfLate(now)) {
                var missing = round.missingInterviewers().stream()
                        .map(value -> {
                            var item = new LinkedHashMap<String, Object>();
                            item.put("name", value.name());
                            if (value.email() != null) item.put("email", value.email());
                            return item;
                        })
                        .toList();
                record("interview.feedback-overdue.v1", round, Map.of("missingInterviewers", missing), now);
                changed = true;
            }
        }
        return changed;
    }

    /** Rejected or withdrawn applications release every interview that has not happened. */
    public boolean close(String reason, Instant now) {
        if (closed) return false;
        closed = true;
        for (var round : rounds) {
            if (Set.of(RoundStatus.UNSCHEDULED, RoundStatus.SCHEDULED).contains(round.status())) {
                cancel(round.id(), reason, now);
            }
        }
        updatedAt = now;
        return true;
    }

    public void recordSchedulingLink(String roundId, String url, Instant expiresAt, Instant now) {
        requireOpen();
        var round = round(roundId);
        if (round.status() != RoundStatus.UNSCHEDULED && round.status() != RoundStatus.CANCELLED)
            throw new IllegalStateException("Send a scheduling link only for a round that is not booked");
        record(
                "interview.scheduling-link.issued.v1",
                round,
                Map.of("bookingUrl", url, "expiresAt", expiresAt.toString()),
                now);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("This application is no longer interviewing");
    }

    private void record(String type, InterviewRound round, Map<String, Object> extra, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("loopId", id);
        payload.put("applicationId", applicationId);
        payload.put("candidateId", candidateId);
        payload.put("jobId", jobId);
        payload.put("jobTitle", jobTitle);
        if (round != null) {
            payload.put("roundId", round.id());
            payload.put("roundName", round.name());
            payload.put("status", round.status().name());
            if (round.slot() != null) {
                payload.put("start", round.slot().start().toString());
                payload.put("end", round.slot().end().toString());
                payload.put("timezone", round.slot().timezone());
            }
            if (round.meetingUrl() != null) payload.put("meetingUrl", round.meetingUrl());
            payload.put(
                    "panel",
                    round.panel().stream()
                            .map(member -> {
                                var item = new LinkedHashMap<String, Object>();
                                item.put("name", member.name());
                                if (member.email() != null) item.put("email", member.email());
                                return item;
                            })
                            .toList());
        }
        if (ownerId != null) payload.put("ownerId", ownerId);
        payload.putAll(extra);
        events.add(new PendingEvent(
                UUID.randomUUID().toString(),
                PRODUCER,
                type,
                1,
                now.toString(),
                tenantId,
                "InterviewLoop",
                id,
                applicationId,
                payload));
        updatedAt = now;
    }

    public List<Scorecard> visibleScorecards(String roundId, Viewer viewer) {
        var round = round(roundId);
        var own = round.panelMember(viewer);
        if (own != null) {
            var mine = round.scorecardOf(own.key());
            if (mine == null) return List.of();
            return round.scorecards();
        }
        if (viewer.coordinator()) return round.scorecards();
        throw new IllegalStateException("Scorecards are visible to the panel after submitting, and to the hiring team");
    }

    public boolean canReadDebrief(Viewer viewer) {
        if (viewer.coordinator()) return true;
        return rounds.stream()
                        .filter(round -> round.onPanel(viewer))
                        .allMatch(round ->
                                round.scorecardOf(round.panelMember(viewer).key()) != null)
                && rounds.stream().anyMatch(round -> round.onPanel(viewer));
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String candidateId() {
        return candidateId;
    }

    public String candidateName() {
        return candidateName;
    }

    public String jobId() {
        return jobId;
    }

    public String jobTitle() {
        return jobTitle;
    }

    public boolean closed() {
        return closed;
    }

    public List<InterviewRound> rounds() {
        return List.copyOf(rounds);
    }

    public List<PendingEvent> pendingEvents() {
        return List.copyOf(events);
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
