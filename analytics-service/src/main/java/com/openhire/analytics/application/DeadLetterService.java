package com.openhire.analytics.application;

import com.openhire.analytics.application.DeadLetterViews.BatchResult;
import com.openhire.analytics.application.DeadLetterViews.DeadLetter;
import com.openhire.analytics.application.DeadLetterViews.DeadLetterDetail;
import com.openhire.analytics.application.DeadLetterViews.ReplayResult;
import com.openhire.analytics.application.port.AnalyticsPorts.AuditTrail;
import com.openhire.analytics.application.port.AnalyticsPorts.DeadLetterSource;
import com.openhire.analytics.application.port.AnalyticsPorts.Replay;
import com.openhire.analytics.application.port.AnalyticsPorts.ReplayLog;
import com.openhire.analytics.application.port.AnalyticsPorts.Republisher;
import com.openhire.analytics.domain.DeadLetterRecord;
import com.openhire.analytics.domain.Redactor;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;

/**
 * Inspects and replays dead letters. Payloads are always redacted on the way out. A message is replayed once: the
 * replay is claimed in {@code dlq_replays} before anything is published, and a super admin may force another.
 */
public class DeadLetterService {
    public static final String REPLAYED_HEADER = "x-replayed-from";
    private static final int PREVIEW_CHARS = 300;
    private static final Pattern RETRY_SUFFIX = Pattern.compile("-retry-\\d+$");

    /** The signed-in operator; the tenant is empty for a super admin without an organisation. */
    public record Operator(String id, String name, String tenant, boolean superAdmin) {}

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class AlreadyReplayed extends RuntimeException {
        public AlreadyReplayed(String id) {
            super("Dead letter " + id + " was already replayed");
        }
    }

    public static class RateLimited extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimited(long retryAfterSeconds) {
            super("Replay limit reached for this organisation; retry in " + retryAfterSeconds + " s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final DeadLetterSource source;
    private final Republisher republisher;
    private final ReplayLog replays;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final int perMinute;
    private final int peekPerPartition;

    public DeadLetterService(
            DeadLetterSource source,
            Republisher republisher,
            ReplayLog replays,
            AuditTrail audit,
            ObjectMapper json,
            Clock clock,
            int perMinute,
            int peekPerPartition) {
        this.source = source;
        this.republisher = republisher;
        this.replays = replays;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.perMinute = perMinute;
        this.peekPerPartition = peekPerPartition;
    }

    public List<DeadLetter> list(Operator operator, int limit) {
        var visible = visible(operator);
        var log = replays.findAll(visible.stream().map(DeadLetterRecord::id).toList()).stream()
                .collect(Collectors.toMap(Replay::id, r -> r));
        return visible.stream()
                .limit(Math.max(1, Math.min(limit, 500)))
                .map(record -> summary(record, log.get(record.id())))
                .toList();
    }

    public DeadLetterDetail detail(Operator operator, String id) {
        var record = find(operator, id);
        var replay = replays.find(id).orElse(null);
        var parsed = parse(record.value());
        var redacted = parsed == null ? "[payload is not JSON and is not shown]" : Redactor.redact(parsed);
        var summary = summary(record, replay);
        return new DeadLetterDetail(
                summary.id(),
                summary.originalTopic(),
                summary.consumerGroup(),
                summary.eventType(),
                summary.tenantId(),
                summary.error(),
                summary.attempts(),
                summary.firstFailedAt(),
                summary.replayedAt(),
                summary.replayedBy(),
                summary.payloadPreview(),
                redacted,
                record.topic(),
                number(record.header("kafka_dlt-original-partition"))
                        .map(Long::intValue)
                        .orElse(null),
                number(record.header("kafka_dlt-original-offset")).orElse(null),
                record.header("kafka_dlt-exception-fqcn"),
                replay == null ? 0 : replay.count());
    }

    public ReplayResult replay(Operator operator, String id, String reason, boolean force) {
        var clean = validReason(reason);
        var record = find(operator, id);
        return replay(operator, record, clean, force);
    }

    public BatchResult replayBatch(Operator operator, String reason, String topic, Integer limit, boolean dryRun) {
        var clean = validReason(reason);
        int max = limit == null ? 20 : limit;
        if (max < 1 || max > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        var visible = visible(operator);
        var done = replays.findAll(visible.stream().map(DeadLetterRecord::id).toList()).stream()
                .map(Replay::id)
                .collect(Collectors.toSet());
        var candidates = visible.stream()
                .filter(record -> !done.contains(record.id()))
                .filter(record -> topic == null
                        || topic.isBlank()
                        || topic.equals(originalTopic(record))
                        || topic.equals(record.topic()))
                .sorted(Comparator.comparing(DeadLetterRecord::timestamp))
                .limit(max)
                .toList();
        if (dryRun) return new BatchResult(true, candidates.size(), 0);
        int replayed = 0;
        for (var record : candidates) {
            try {
                replay(operator, record, clean, false);
                replayed++;
            } catch (AlreadyReplayed raced) {
                // Another operator replayed it between the listing and now; it is not ours to count.
            } catch (RateLimited limited) {
                if (replayed == 0) throw limited;
                break;
            }
        }
        return new BatchResult(false, candidates.size(), replayed);
    }

    private ReplayResult replay(Operator operator, DeadLetterRecord record, String reason, boolean force) {
        var id = record.id();
        var tenant = tenantOf(record);
        var existing = replays.find(id);
        if (existing.isPresent() && !(force && operator.superAdmin())) throw new AlreadyReplayed(id);
        var now = clock.instant();
        var recent = replays.since(rateKey(tenant), now.minus(Duration.ofMinutes(1)));
        if (recent.size() >= perMinute) {
            var oldest = recent.stream().min(Comparator.naturalOrder()).orElse(now);
            throw new RateLimited(
                    Math.max(1, Duration.between(now, oldest.plusSeconds(60)).toSeconds() + 1));
        }
        var topic = originalTopic(record);
        if (topic.isBlank()) throw new IllegalStateException("The original topic of " + id + " is unknown");
        var claim = new Replay(
                id,
                rateKey(tenant),
                topic,
                reason,
                operator.id(),
                now,
                existing.map(r -> r.count() + 1).orElse(1),
                existing.isPresent());
        if (existing.isPresent()) replays.update(claim);
        else if (!replays.insert(claim)) throw new AlreadyReplayed(id);
        try {
            republisher.publish(topic, record.key(), record.value(), Map.of(REPLAYED_HEADER, id));
        } catch (RuntimeException failure) {
            if (existing.isPresent()) replays.update(existing.get());
            else replays.delete(id);
            throw failure;
        }
        audit.deadLetterReplayed(
                tenant.isBlank() ? (operator.tenant().isBlank() ? "platform" : operator.tenant()) : tenant,
                operator.id(),
                operator.name(),
                id,
                topic);
        return new ReplayResult(id, now.toString(), operator.id(), claim.count());
    }

    private static String rateKey(String tenant) {
        return tenant.isBlank() ? "unknown" : tenant;
    }

    private static String validReason(String reason) {
        var clean = reason == null ? "" : reason.trim();
        if (clean.length() < 5 || clean.length() > 500)
            throw new IllegalArgumentException("reason is required (5 to 500 characters)");
        return clean;
    }

    private List<DeadLetterRecord> visible(Operator operator) {
        return source.recent(peekPerPartition).stream()
                .filter(record -> visibleTo(operator, record))
                .sorted(Comparator.comparing(DeadLetterRecord::timestamp).reversed())
                .toList();
    }

    private DeadLetterRecord find(Operator operator, String id) {
        var parts = id == null ? new String[0] : id.split(":");
        if (parts.length != 3) throw new NotFound("Dead letter not found");
        try {
            return source.find(parts[0], Integer.parseInt(parts[1]), Long.parseLong(parts[2]))
                    .filter(record -> visibleTo(operator, record))
                    .orElseThrow(() -> new NotFound("Dead letter not found"));
        } catch (NumberFormatException invalid) {
            throw new NotFound("Dead letter not found");
        }
    }

    private boolean visibleTo(Operator operator, DeadLetterRecord record) {
        if (operator.superAdmin()) return true;
        var tenant = tenantOf(record);
        return !tenant.isBlank() && tenant.equals(operator.tenant());
    }

    private DeadLetter summary(DeadLetterRecord record, Replay replay) {
        var envelope = parse(record.value());
        var eventType = envelope == null ? "" : String.valueOf(envelope.getOrDefault("eventType", ""));
        var original = number(record.header("kafka_dlt-original-timestamp"))
                .map(java.time.Instant::ofEpochMilli)
                .orElse(record.timestamp());
        return new DeadLetter(
                record.id(),
                originalTopic(record),
                record.header("kafka_dlt-original-consumer-group"),
                eventType,
                tenantOf(record),
                Redactor.text(truncate(record.header("kafka_dlt-exception-message"), 500)),
                number(record.header("kafka_retryTopicAttempts"))
                        .map(Long::intValue)
                        .orElse(null),
                original.toString(),
                replay == null ? null : replay.replayedAt().toString(),
                replay == null ? null : replay.replayedBy(),
                preview(envelope));
    }

    private String preview(Map<String, Object> envelope) {
        if (envelope == null) return "[payload is not JSON]";
        try {
            return truncate(json.writeValueAsString(Redactor.redact(envelope)), PREVIEW_CHARS);
        } catch (RuntimeException unreadable) {
            return "[payload could not be rendered]";
        }
    }

    private String tenantOf(DeadLetterRecord record) {
        var envelope = parse(record.value());
        return envelope == null
                ? ""
                : String.valueOf(envelope.getOrDefault("tenantId", "")).trim();
    }

    /** A retry topic's message goes back to the topic it was retried from. */
    static String originalTopic(DeadLetterRecord record) {
        var header = record.header("kafka_dlt-original-topic");
        if (header.isBlank()) header = record.topic().replaceFirst("(?i)([.-])dlt$", "");
        return RETRY_SUFFIX.matcher(header).replaceFirst("");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        try {
            return value == null ? null : json.readValue(value, Map.class);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static Optional<Long> number(String text) {
        try {
            return text == null || text.isBlank() ? Optional.empty() : Optional.of(Long.parseLong(text.trim()));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
