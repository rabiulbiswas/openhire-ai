package com.openhire.interview.application;

import java.util.List;

/** Read models returned by the interview API; field names match the console's TypeScript contracts. */
public final class InterviewViews {
    private InterviewViews() {}

    public record Page<T>(List<T> data, String nextCursor, boolean hasMore) {
        public static <T> Page<T> of(List<T> data) {
            return new Page<>(data, null, false);
        }
    }

    public record PanelView(String name, String email, String role) {}

    public record InterviewEventView(
            String id,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobTitle,
            String roundName,
            String start,
            int durationMin,
            String interviewerName,
            String status,
            boolean scorecardSubmitted) {}

    public record UnscheduledRoundView(
            String roundId,
            String loopId,
            String applicationId,
            String candidateName,
            String jobTitle,
            String roundName,
            int durationMin,
            List<PanelView> panel,
            String status) {}

    public record RoundView(
            String id,
            int seq,
            String name,
            int durationMin,
            String status,
            String start,
            String end,
            String timezone,
            List<PanelView> panel,
            String meetingUrl,
            int rescheduleCount,
            String feedbackDueAt,
            int scorecardsSubmitted,
            int scorecardsExpected) {}

    public record LoopView(
            String loopId,
            String applicationId,
            String candidateName,
            String jobTitle,
            boolean closed,
            List<RoundView> rounds) {}

    public record CompetencyView(String id, String label, String rating) {}

    public record ScorecardView(
            String roundId,
            String candidateName,
            String roundName,
            List<CompetencyView> competencies,
            String evidence,
            boolean submitted,
            List<String> aiSuggestedQuestions) {}

    public record SubmittedScorecardView(
            String interviewerName,
            List<CompetencyView> ratings,
            String evidence,
            String recommendation,
            String submittedAt) {}

    public record MyInterviewView(
            String roundId,
            String applicationId,
            String candidateName,
            String jobTitle,
            String roundName,
            String start,
            int durationMin,
            String status,
            String myScorecard,
            String feedbackDueAt) {}

    public record SchedulingLinkView(String url, String expiresAt, List<String> slots) {}

    public record PublicSchedulingView(
            String jobTitle,
            String roundName,
            int durationMin,
            String timezone,
            List<String> slots,
            String holdExpiresAt,
            String bookedStart,
            String status,
            boolean canChange,
            String changeNote) {}
}
