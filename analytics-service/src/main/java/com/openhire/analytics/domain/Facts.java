package com.openhire.analytics.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/** The smaller materialized views. Each is written by exactly one event family so projections never race. */
public final class Facts {
    private Facts() {}

    public static final Set<String> OVERRIDE_REASONS =
            Set.of("UNDERWEIGHTED_EVIDENCE", "OVERWEIGHTED_GAP", "CONTEXT_NOT_IN_RESUME", "OTHER");

    public static String overrideReason(String code) {
        var normalized = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        return OVERRIDE_REASONS.contains(normalized) ? normalized : "OTHER";
    }

    public record AssessmentFact(
            String tenantId,
            String assessmentId,
            String applicationId,
            String jobId,
            int score,
            String confidence,
            String model,
            String status,
            Instant completedAt,
            Instant screenedAt) {
        public boolean complete() {
            return "COMPLETE".equals(status);
        }
    }

    public enum DecisionKind {
        SHORTLIST,
        REJECT,
        OVERRIDE
    }

    /** A human decision. Only people shortlist, reject or override, so the actor type is always USER. */
    public record DecisionFact(
            String tenantId,
            String id,
            String applicationId,
            String jobId,
            DecisionKind kind,
            String actorType,
            String actorId,
            Instant at,
            String fromStage,
            String toStage,
            String reasonCode,
            Integer newScore,
            boolean undone) {
        private static final Set<String> SCREENING = Set.of("APPLIED", "NEEDS_ATTENTION", "AI_ASSESSED");

        /** A shortlist or rejection made at screening, which is where an AI recommendation can be compared. */
        public boolean screening() {
            return kind != DecisionKind.OVERRIDE && fromStage != null && SCREENING.contains(fromStage);
        }

        public DecisionFact undo() {
            return new DecisionFact(
                    tenantId,
                    id,
                    applicationId,
                    jobId,
                    kind,
                    actorType,
                    actorId,
                    at,
                    fromStage,
                    toStage,
                    reasonCode,
                    newScore,
                    true);
        }
    }

    public record OfferFact(
            String tenantId,
            String offerId,
            String applicationId,
            String jobId,
            Instant createdAt,
            Instant sentAt,
            Instant acceptedAt,
            Instant declinedAt,
            Instant expiredAt,
            Instant rescindedAt,
            Instant lastEventAt) {
        public static OfferFact empty(String tenantId, String offerId) {
            return new OfferFact(tenantId, offerId, null, null, null, null, null, null, null, null, null);
        }

        public OfferFact with(String type, Instant at, String applicationId, String jobId) {
            return new OfferFact(
                    tenantId,
                    offerId,
                    blank(applicationId) ? this.applicationId : applicationId,
                    blank(jobId) ? this.jobId : jobId,
                    "offer.created.v1".equals(type) ? first(createdAt, at) : createdAt,
                    "offer.sent.v1".equals(type) ? first(sentAt, at) : sentAt,
                    "offer.accepted.v1".equals(type) ? first(acceptedAt, at) : acceptedAt,
                    "offer.declined.v1".equals(type) ? first(declinedAt, at) : declinedAt,
                    "offer.expired.v1".equals(type) ? first(expiredAt, at) : expiredAt,
                    "offer.rescinded.v1".equals(type) ? first(rescindedAt, at) : rescindedAt,
                    lastEventAt == null || at.isAfter(lastEventAt) ? at : lastEventAt);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static Instant first(Instant current, Instant at) {
            return current == null || at.isBefore(current) ? at : current;
        }
    }

    /** One AI request, folded into the day, task and model it belongs to. */
    public record AiSample(
            String tenantId,
            LocalDate day,
            String task,
            String model,
            boolean error,
            long promptTokens,
            long completionTokens,
            double costInr,
            long latencyMs) {}

    /** Upper bounds in milliseconds of the latency histogram; the last bucket is open ended. */
    public static final long[] LATENCY_BOUNDS = {100, 250, 500, 1000, 2500, 5000, 10000, 20000, 30000, 60000};

    public static int bucket(long latencyMs) {
        for (int i = 0; i < LATENCY_BOUNDS.length; i++) if (latencyMs <= LATENCY_BOUNDS[i]) return i;
        return LATENCY_BOUNDS.length;
    }

    public record AiDayFact(
            String tenantId,
            LocalDate day,
            String task,
            String model,
            long requests,
            long errors,
            long promptTokens,
            long completionTokens,
            double costInr,
            long latencyMsSum,
            long[] latencyBuckets) {
        /** Upper bound of the bucket holding the 95th percentile request, or the largest bound for the open bucket. */
        public long p95Ms() {
            return percentileMs(latencyBuckets, 0.95);
        }
    }

    public static long percentileMs(long[] buckets, double quantile) {
        long total = 0;
        for (long count : buckets) total += count;
        if (total == 0) return 0;
        long rank = (long) Math.ceil(quantile * total);
        long seen = 0;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= rank) return LATENCY_BOUNDS[Math.min(i, LATENCY_BOUNDS.length - 1)];
        }
        return LATENCY_BOUNDS[LATENCY_BOUNDS.length - 1];
    }

    public enum LatencyKind {
        RESUME_PROCESSING,
        AI_ASSESSMENT,
        NOTIFICATION_HANDOFF
    }

    public enum Edge {
        START,
        END
    }

    /** Start and end event times of one unit of work (an application or a message), in either arrival order. */
    public record LatencyFact(String tenantId, LatencyKind kind, String key, Instant startAt, Instant endAt) {
        public Optional<Duration> duration() {
            if (startAt == null || endAt == null) return Optional.empty();
            var value = Duration.between(startAt, endAt);
            return Optional.of(value.isNegative() ? Duration.ZERO : value);
        }
    }
}
