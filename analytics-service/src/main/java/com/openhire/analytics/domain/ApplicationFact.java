package com.openhire.analytics.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** One application's journey as seen through events: who and where it came from, when it entered each stage. */
public record ApplicationFact(
        String tenantId,
        String applicationId,
        String jobId,
        String jobTitle,
        String source,
        Instant submittedAt,
        Instant screenedAt,
        List<StageEntry> timeline,
        Instant hiredAt,
        Instant rejectedAt,
        Instant withdrawnAt,
        Instant lastEventAt) {
    public record StageEntry(String stage, Instant at) {}

    public static final Set<String> TERMINAL = Set.of("HIRED", "REJECTED", "WITHDRAWN");

    public ApplicationFact {
        timeline = timeline == null
                ? List.of()
                : timeline.stream().sorted(Comparator.comparing(StageEntry::at)).toList();
    }

    public static ApplicationFact empty(String tenantId, String applicationId) {
        return new ApplicationFact(
                tenantId, applicationId, null, null, null, null, null, List.of(), null, null, null, null);
    }

    public ApplicationFact withJob(String jobId, String jobTitle, String source) {
        return new ApplicationFact(
                tenantId,
                applicationId,
                present(jobId) ? jobId : this.jobId,
                present(jobTitle) ? jobTitle : this.jobTitle,
                present(source) ? source : this.source,
                submittedAt,
                screenedAt,
                timeline,
                hiredAt,
                rejectedAt,
                withdrawnAt,
                lastEventAt);
    }

    public ApplicationFact withSubmitted(Instant at) {
        var result = with(earliest(submittedAt, at), screenedAt, hiredAt, rejectedAt, withdrawnAt, timeline, at);
        return result.enter("APPLIED", at);
    }

    /** Keyword screening moves the application to AI_ASSESSED without a stage-change event. */
    public ApplicationFact withScreened(Instant at) {
        return with(submittedAt, earliest(screenedAt, at), hiredAt, rejectedAt, withdrawnAt, timeline, at)
                .enter("AI_ASSESSED", at);
    }

    public ApplicationFact withStage(String stage, Instant at) {
        var result = enter(stage, at);
        return result.with(
                submittedAt,
                screenedAt,
                "HIRED".equals(stage) ? earliest(hiredAt, at) : hiredAt,
                "REJECTED".equals(stage) ? latest(rejectedAt, at) : rejectedAt,
                "WITHDRAWN".equals(stage) ? earliest(withdrawnAt, at) : withdrawnAt,
                result.timeline,
                at);
    }

    /** An undone shortlist or rejection never happened for the funnel; the previous stage resumes. */
    public ApplicationFact withDecisionUndone() {
        if (timeline.isEmpty()) return this;
        var last = timeline.getLast();
        if (!last.stage().equals("SHORTLISTED") && !last.stage().equals("REJECTED")) return this;
        var kept = new ArrayList<>(timeline.subList(0, timeline.size() - 1));
        return with(
                submittedAt,
                screenedAt,
                hiredAt,
                "REJECTED".equals(last.stage()) ? null : rejectedAt,
                withdrawnAt,
                kept,
                lastEventAt);
    }

    public boolean reached(String stage) {
        return timeline.stream().anyMatch(entry -> entry.stage().equals(stage));
    }

    private ApplicationFact enter(String stage, Instant at) {
        if (timeline.stream().anyMatch(e -> e.stage().equals(stage) && e.at().equals(at))) return this;
        var entries = new ArrayList<>(timeline);
        entries.add(new StageEntry(stage, at));
        return with(submittedAt, screenedAt, hiredAt, rejectedAt, withdrawnAt, entries, at);
    }

    private ApplicationFact with(
            Instant submitted,
            Instant screened,
            Instant hired,
            Instant rejected,
            Instant withdrawn,
            List<StageEntry> entries,
            Instant eventAt) {
        return new ApplicationFact(
                tenantId,
                applicationId,
                jobId,
                jobTitle,
                source,
                submitted,
                screened,
                entries,
                hired,
                rejected,
                withdrawn,
                latest(lastEventAt, eventAt));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant earliest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
