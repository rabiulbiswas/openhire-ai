package com.openhire.interview.adapter.in.web;

import com.openhire.interview.application.InterviewApplicationService;
import com.openhire.interview.application.InterviewViews.InterviewEventView;
import com.openhire.interview.application.InterviewViews.LoopView;
import com.openhire.interview.application.InterviewViews.MyInterviewView;
import com.openhire.interview.application.InterviewViews.Page;
import com.openhire.interview.application.InterviewViews.RoundView;
import com.openhire.interview.application.InterviewViews.SchedulingLinkView;
import com.openhire.interview.application.InterviewViews.ScorecardView;
import com.openhire.interview.application.InterviewViews.SubmittedScorecardView;
import com.openhire.interview.application.InterviewViews.UnscheduledRoundView;
import com.openhire.interview.domain.DebriefBuilder;
import com.openhire.interview.domain.InterviewTypes.PanelMember;
import com.openhire.interview.domain.InterviewTypes.Rating;
import com.openhire.interview.domain.InterviewTypes.RatingEntry;
import com.openhire.interview.domain.InterviewTypes.Recommendation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class InterviewController {
    private final InterviewApplicationService interviews;
    private final Viewers viewers;

    InterviewController(InterviewApplicationService interviews, Viewers viewers) {
        this.interviews = interviews;
        this.viewers = viewers;
    }

    record PanelInput(String name, String email, String role) {}

    record ScheduleInput(String start, String timezone, List<PanelInput> panel, String meetingUrl) {}

    record RescheduleInput(String start) {}

    record CancelInput(String reason) {}

    record RatingInput(String competencyId, String rating) {}

    record ScorecardInput(List<RatingInput> ratings, String evidence, String recommendation) {}

    @PostMapping("/applications/{applicationId}/interview-loop")
    @ResponseStatus(HttpStatus.CREATED)
    LoopView createLoop(Authentication auth, @PathVariable String applicationId) {
        var viewer = viewers.coordinator(auth);
        return interviews.createLoop(viewers.tenant(auth), applicationId, viewer.subject());
    }

    @GetMapping("/applications/{applicationId}/interview-loop")
    LoopView loop(Authentication auth, @PathVariable String applicationId) {
        viewers.coordinator(auth);
        return interviews.loop(viewers.tenant(auth), applicationId);
    }

    @GetMapping("/interviews")
    Page<InterviewEventView> week(Authentication auth, @RequestParam(required = false) String week) {
        var viewer = viewers.participant(auth);
        LocalDate day;
        try {
            day = week == null || week.isBlank() ? LocalDate.now() : LocalDate.parse(week);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("week must be a date such as 2026-10-12");
        }
        return interviews.week(viewers.tenant(auth), day, viewer);
    }

    @GetMapping("/interviews/unscheduled")
    Page<UnscheduledRoundView> unscheduled(Authentication auth) {
        viewers.coordinator(auth);
        return interviews.unscheduled(viewers.tenant(auth));
    }

    @GetMapping("/me/interviews")
    Page<MyInterviewView> mine(Authentication auth) {
        var viewer = viewers.participant(auth);
        return interviews.myInterviews(viewers.tenant(auth), viewer);
    }

    @PostMapping("/rounds/{roundId}/schedule")
    RoundView schedule(Authentication auth, @PathVariable String roundId, @RequestBody ScheduleInput input) {
        var actor = viewers.coordinator(auth);
        var panel = input.panel() == null
                ? List.<PanelMember>of()
                : input.panel().stream()
                        .map(value -> new PanelMember(value.name(), value.email(), value.role()))
                        .toList();
        return interviews.schedule(
                viewers.tenant(auth),
                roundId,
                instant(input.start()),
                input.timezone(),
                panel,
                input.meetingUrl(),
                actor);
    }

    record PanelAssignment(List<PanelInput> panel) {}

    @PutMapping("/rounds/{roundId}/panel")
    RoundView assignPanel(Authentication auth, @PathVariable String roundId, @RequestBody PanelAssignment input) {
        viewers.coordinator(auth);
        var panel = input.panel() == null
                ? List.<PanelMember>of()
                : input.panel().stream()
                        .map(value -> new PanelMember(value.name(), value.email(), value.role()))
                        .toList();
        return interviews.assignPanel(viewers.tenant(auth), roundId, panel);
    }

    @PostMapping("/rounds/{roundId}/reschedule")
    Map<String, String> reschedule(
            Authentication auth, @PathVariable String roundId, @RequestBody RescheduleInput input) {
        viewers.coordinator(auth);
        interviews.reschedule(viewers.tenant(auth), roundId, instant(input.start()));
        return Map.of("status", "RESCHEDULED");
    }

    @PostMapping("/rounds/{roundId}/cancel")
    Map<String, String> cancel(
            Authentication auth, @PathVariable String roundId, @RequestBody(required = false) CancelInput input) {
        var actor = viewers.coordinator(auth);
        interviews.cancel(viewers.tenant(auth), roundId, input == null ? null : input.reason(), actor);
        return Map.of("status", "CANCELLED");
    }

    @PostMapping("/rounds/{roundId}/no-show")
    Map<String, String> noShow(Authentication auth, @PathVariable String roundId) {
        viewers.coordinator(auth);
        interviews.noShow(viewers.tenant(auth), roundId);
        return Map.of("status", "NO_SHOW");
    }

    @PostMapping("/rounds/{roundId}/complete")
    Map<String, String> complete(Authentication auth, @PathVariable String roundId) {
        viewers.coordinator(auth);
        interviews.complete(viewers.tenant(auth), roundId);
        return Map.of("status", "COMPLETED");
    }

    @PostMapping("/rounds/{roundId}/scheduling-link")
    SchedulingLinkView schedulingLink(Authentication auth, @PathVariable String roundId) {
        viewers.coordinator(auth);
        return interviews.schedulingLink(viewers.tenant(auth), roundId);
    }

    @GetMapping("/rounds/{roundId}/scorecard")
    ScorecardView scorecard(Authentication auth, @PathVariable String roundId) {
        return interviews.myScorecard(viewers.tenant(auth), roundId, viewers.participant(auth));
    }

    @PostMapping("/rounds/{roundId}/scorecards")
    Map<String, String> submit(Authentication auth, @PathVariable String roundId, @RequestBody ScorecardInput input) {
        var viewer = viewers.participant(auth);
        var ratings = input.ratings() == null
                ? List.<RatingEntry>of()
                : input.ratings().stream()
                        .map(value -> new RatingEntry(
                                value.competencyId(), enumValue(Rating.class, value.rating(), "rating")))
                        .toList();
        var recommendation =
                input.recommendation() == null || input.recommendation().isBlank()
                        ? null
                        : enumValue(Recommendation.class, input.recommendation(), "recommendation");
        interviews.submitScorecard(viewers.tenant(auth), roundId, viewer, ratings, input.evidence(), recommendation);
        return Map.of("status", "SUBMITTED");
    }

    @GetMapping("/rounds/{roundId}/scorecards")
    Map<String, List<SubmittedScorecardView>> scorecards(Authentication auth, @PathVariable String roundId) {
        return Map.of("data", interviews.scorecards(viewers.tenant(auth), roundId, viewers.participant(auth)));
    }

    @GetMapping("/applications/{applicationId}/debrief")
    DebriefBuilder.Debrief debrief(Authentication auth, @PathVariable String applicationId) {
        return interviews.debrief(viewers.tenant(auth), applicationId, viewers.participant(auth));
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("start is required");
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("start must be an ISO-8601 date-time with an offset");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
    }
}
