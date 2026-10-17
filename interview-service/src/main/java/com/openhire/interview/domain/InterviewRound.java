package com.openhire.interview.domain;

import com.openhire.interview.domain.InterviewTypes.Competency;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.Recommendation;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import com.openhire.interview.domain.InterviewTypes.Scorecard;
import com.openhire.interview.domain.InterviewTypes.Slot;
import com.openhire.interview.domain.InterviewTypes.Viewer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One interview round inside a loop. All state changes go through the owning {@link InterviewLoop}. */
public class InterviewRound {
    private String roundId;
    private int seq;
    private String name;
    private int durationMin;
    private List<PanelMember> panel;
    private RoundStatus status;
    private Slot slot;
    private String meetingUrl;
    private List<Competency> competencies;
    private List<String> kitQuestions;
    private Instant feedbackDueAt;
    private int rescheduleCount;
    private boolean feedbackComplete;
    private List<Scorecard> scorecards;
    private String cancelReason;

    protected InterviewRound() {}

    InterviewRound(
            String id, int seq, String name, int durationMin, List<PanelMember> panel, List<Competency> competencies) {
        if (durationMin < 15 || durationMin > 480)
            throw new IllegalArgumentException("Round duration must be 15-480 minutes");
        this.roundId = id;
        this.seq = seq;
        this.name = name;
        this.durationMin = durationMin;
        this.panel = new ArrayList<>(panel);
        this.status = RoundStatus.UNSCHEDULED;
        this.competencies = List.copyOf(competencies);
        this.kitQuestions = List.of();
        this.scorecards = new ArrayList<>();
    }

    void schedule(Slot next, List<PanelMember> members, String meeting, Duration feedbackWindow) {
        if (!Set.of(RoundStatus.UNSCHEDULED, RoundStatus.CANCELLED, RoundStatus.NO_SHOW)
                .contains(status)) throw new IllegalStateException("Round " + name + " is already " + status);
        if (members.isEmpty()) throw new IllegalArgumentException("Assign at least one interviewer");
        requireDuration(next);
        if (status != RoundStatus.UNSCHEDULED) rescheduleCount++;
        panel = new ArrayList<>(members);
        slot = next;
        meetingUrl = meeting;
        status = RoundStatus.SCHEDULED;
        feedbackDueAt = next.end().plus(feedbackWindow);
        cancelReason = null;
        feedbackComplete = false;
    }

    void assignPanel(List<PanelMember> members) {
        if (!Set.of(RoundStatus.UNSCHEDULED, RoundStatus.CANCELLED, RoundStatus.NO_SHOW)
                .contains(status))
            throw new IllegalStateException("Change the panel of a booked round by rescheduling it");
        if (members.isEmpty()) throw new IllegalArgumentException("Assign at least one interviewer");
        panel = new ArrayList<>(members);
    }

    void reschedule(Slot next, Instant now, int limit, Duration feedbackWindow) {
        if (status != RoundStatus.SCHEDULED)
            throw new IllegalStateException("Only a scheduled round can be rescheduled");
        if (!next.start().isAfter(now)) throw new IllegalArgumentException("Choose a future time");
        if (rescheduleCount >= limit)
            throw new IllegalStateException(
                    "This round has been rescheduled " + limit + " times; cancel and rebook instead");
        requireDuration(next);
        rescheduleCount++;
        slot = next;
        feedbackDueAt = next.end().plus(feedbackWindow);
    }

    void cancel(String reason) {
        if (!Set.of(RoundStatus.UNSCHEDULED, RoundStatus.SCHEDULED).contains(status))
            throw new IllegalStateException("A " + status + " round cannot be cancelled");
        status = RoundStatus.CANCELLED;
        cancelReason = reason;
    }

    void markNoShow(Instant now) {
        if (status != RoundStatus.SCHEDULED) throw new IllegalStateException("Only a scheduled round can be a no-show");
        if (slot.start().isAfter(now)) throw new IllegalStateException("The round has not started yet");
        status = RoundStatus.NO_SHOW;
    }

    void complete(Instant now) {
        if (status != RoundStatus.SCHEDULED) throw new IllegalStateException("Only a scheduled round can be completed");
        if (slot.start().isAfter(now)) throw new IllegalStateException("The round has not started yet");
        status = RoundStatus.COMPLETED;
    }

    void submit(Viewer viewer, List<RatingEntry> ratings, String evidence, Recommendation recommendation, Instant now) {
        var member = panel.stream()
                .filter(value -> value.isViewer(viewer))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Only this round's interviewers can submit a scorecard"));
        boolean started = slot != null && !slot.start().isAfter(now);
        if (!(status == RoundStatus.COMPLETED
                || status == RoundStatus.FEEDBACK_OVERDUE
                || (status == RoundStatus.SCHEDULED && started)))
            throw new IllegalStateException("Scorecards open once the round has started");
        if (scorecardOf(member.key()) != null)
            throw new IllegalStateException("Your scorecard is already submitted and locked");
        var rated = new HashSet<String>();
        for (var entry : ratings) {
            if (competencies.stream().noneMatch(value -> value.id().equals(entry.competencyId())))
                throw new IllegalArgumentException("Unknown competency " + entry.competencyId());
            if (entry.rating() == null) throw new IllegalArgumentException("Rate every competency");
            rated.add(entry.competencyId());
        }
        if (rated.size() != competencies.size()) throw new IllegalArgumentException("Rate every competency");
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("Evidence notes are required");
        scorecards.add(new Scorecard(
                member.key(),
                viewer.subject(),
                member.name(),
                List.copyOf(ratings),
                evidence.trim(),
                recommendation,
                now));
        feedbackComplete = missingInterviewers().isEmpty();
        if (status == RoundStatus.SCHEDULED) status = RoundStatus.COMPLETED;
        if (status == RoundStatus.FEEDBACK_OVERDUE && missingInterviewers().isEmpty()) status = RoundStatus.COMPLETED;
    }

    boolean completeIfEnded(Instant now) {
        if (status == RoundStatus.SCHEDULED && !slot.end().isAfter(now)) {
            status = RoundStatus.COMPLETED;
            return true;
        }
        return false;
    }

    boolean markOverdueIfLate(Instant now) {
        if (status == RoundStatus.COMPLETED
                && feedbackDueAt != null
                && !feedbackDueAt.isAfter(now)
                && !missingInterviewers().isEmpty()) {
            status = RoundStatus.FEEDBACK_OVERDUE;
            return true;
        }
        return false;
    }

    public List<PanelMember> missingInterviewers() {
        return panel.stream().filter(value -> scorecardOf(value.key()) == null).toList();
    }

    public Scorecard scorecardOf(String interviewerKey) {
        return scorecards.stream()
                .filter(value -> value.interviewerKey().equals(interviewerKey))
                .findFirst()
                .orElse(null);
    }

    public boolean onPanel(Viewer viewer) {
        return panel.stream().anyMatch(value -> value.isViewer(viewer));
    }

    public PanelMember panelMember(Viewer viewer) {
        return panel.stream()
                .filter(value -> value.isViewer(viewer))
                .findFirst()
                .orElse(null);
    }

    private void requireDuration(Slot next) {
        if (Duration.between(next.start(), next.end()).toMinutes() != durationMin)
            throw new IllegalArgumentException("The slot must last " + durationMin + " minutes");
    }

    public String id() {
        return roundId;
    }

    public int seq() {
        return seq;
    }

    public String name() {
        return name;
    }

    public int durationMin() {
        return durationMin;
    }

    public List<PanelMember> panel() {
        return List.copyOf(panel);
    }

    public RoundStatus status() {
        return status;
    }

    public Slot slot() {
        return slot;
    }

    public String meetingUrl() {
        return meetingUrl;
    }

    public List<Competency> competencies() {
        return competencies;
    }

    public List<String> kitQuestions() {
        return kitQuestions;
    }

    public Instant feedbackDueAt() {
        return feedbackDueAt;
    }

    public int rescheduleCount() {
        return rescheduleCount;
    }

    public List<Scorecard> scorecards() {
        return List.copyOf(scorecards);
    }

    public boolean feedbackComplete() {
        return feedbackComplete;
    }

    public String cancelReason() {
        return cancelReason;
    }
}
