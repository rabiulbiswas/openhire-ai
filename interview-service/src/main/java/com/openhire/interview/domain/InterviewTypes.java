package com.openhire.interview.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Value types of the interview loop aggregate. */
public final class InterviewTypes {
    private InterviewTypes() {}

    public enum RoundStatus {
        UNSCHEDULED,
        SCHEDULED,
        COMPLETED,
        FEEDBACK_OVERDUE,
        CANCELLED,
        NO_SHOW
    }

    /** Anchored 1-4 scale from the design; the UI labels are POOR, MIXED, GOOD and EXCELLENT. */
    public enum Rating {
        POOR(1),
        MIXED(2),
        GOOD(3),
        EXCELLENT(4);

        private final int score;

        Rating(int score) {
            this.score = score;
        }

        public int score() {
            return score;
        }
    }

    public enum Recommendation {
        STRONG_NO,
        NO,
        YES,
        STRONG_YES
    }

    public record PanelMember(String name, String email, String role) {
        public PanelMember {
            if ((name == null || name.isBlank()) && (email == null || email.isBlank()))
                throw new IllegalArgumentException("A panel member needs a name or an email");
            name = name == null || name.isBlank() ? email.trim() : name.trim();
            email = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
            role = "SHADOW".equals(role) ? "SHADOW" : "LEAD";
        }

        /** Stable identity for bookings and scorecards: email when known, otherwise the display name. */
        public String key() {
            return email != null ? "email:" + email : "name:" + name.toLowerCase(Locale.ROOT);
        }

        public boolean isViewer(Viewer viewer) {
            if (email != null && viewer.email() != null) return email.equalsIgnoreCase(viewer.email());
            return viewer.name() != null && name.equalsIgnoreCase(viewer.name());
        }
    }

    public record Slot(Instant start, Instant end, String timezone) {
        public Slot {
            if (start == null || end == null || !end.isAfter(start))
                throw new IllegalArgumentException("A slot needs a start before its end");
            if (timezone == null || timezone.isBlank()) timezone = "UTC";
            java.time.ZoneId.of(timezone);
        }
    }

    public record Competency(String id, String label) {}

    public record RatingEntry(String competencyId, Rating rating) {}

    public record Scorecard(
            String interviewerKey,
            String interviewerId,
            String interviewerName,
            List<RatingEntry> ratings,
            String evidence,
            Recommendation recommendation,
            Instant submittedAt) {}

    public record RoundPlan(String name, int durationMin, List<String> interviewerNames) {}

    /** The authenticated person asking; drives panel membership and blind-feedback rules. */
    public record Viewer(String subject, String email, String name, Set<String> roles) {
        private static final Set<String> COORDINATORS =
                Set.of("RECRUITER", "ORG_ADMIN", "HR_ADMIN", "HIRING_MANAGER", "SUPER_ADMIN");

        public boolean coordinator() {
            return roles.stream().anyMatch(COORDINATORS::contains);
        }
    }

    /** Pending integration event, stored inside the aggregate so it commits atomically with the change. */
    public record PendingEvent(
            String eventId,
            String producer,
            String eventType,
            int eventVersion,
            String occurredAt,
            String tenantId,
            String aggregateType,
            String aggregateId,
            String correlationId,
            Map<String, Object> payload) {}
}
