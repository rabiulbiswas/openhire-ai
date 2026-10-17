package com.openhire.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One requested message's delivery. The id is the message id, so a redelivered command finds the
 * existing record instead of sending twice. Content is erased once sent; failed content is kept for an
 * operator retry until the retention purge removes it.
 */
@Document("deliveries")
@CompoundIndexes({
    @CompoundIndex(name = "status_due", def = "{'status': 1, 'dueAt': 1}"),
    @CompoundIndex(name = "tenant_status_updated", def = "{'tenantId': 1, 'status': 1, 'updatedAt': -1}"),
    @CompoundIndex(name = "tenant_conversation", def = "{'tenantId': 1, 'conversationId': 1}", sparse = true)
})
public class Delivery {
    public enum Status {
        PENDING,
        DEFERRED,
        SENT,
        FAILED
    }

    public enum Category {
        TRANSACTIONAL,
        NOTIFICATION
    }

    public record Recipient(String name, String email, String phone) {}

    public record QuietHours(String from, String to, String timezone) {
        /** End of the quiet window containing {@code now}, or null when {@code now} is outside it. Handles overnight windows. */
        public Instant endIfInside(Instant now) {
            var zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
            var local = ZonedDateTime.ofInstant(now, zone);
            var start = LocalTime.parse(from);
            var end = LocalTime.parse(to);
            var time = local.toLocalTime();
            boolean overnight = start.isAfter(end);
            boolean inside = overnight
                    ? !time.isBefore(start) || time.isBefore(end)
                    : !time.isBefore(start) && time.isBefore(end);
            if (!inside) return null;
            var endDate =
                    overnight && !time.isBefore(start) ? local.toLocalDate().plusDays(1) : local.toLocalDate();
            return ZonedDateTime.of(endDate, end, zone).toInstant();
        }
    }

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

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String audience;
    private String requestedChannel;
    private String fallbackChannel;
    private String channelUsed;
    private Recipient recipient;
    private String subject;
    private String body;
    private Category category;
    private QuietHours quietHours;
    private Status status;
    private String reason;
    private int attempts;
    private Instant dueAt;
    private Instant createdAt;
    private Instant updatedAt;

    /** The candidate conversation the message belongs to, so a privacy request can find the candidate's deliveries. */
    private String conversationId;

    @Field("_events")
    private List<PendingEvent> events = new ArrayList<>();

    protected Delivery() {}

    public static Delivery request(
            String messageId,
            String tenantId,
            String audience,
            String channel,
            String fallbackChannel,
            Recipient recipient,
            String subject,
            String body,
            Category category,
            QuietHours quietHours,
            Instant now) {
        var value = new Delivery();
        value.id = messageId;
        value.tenantId = tenantId;
        value.audience = audience;
        value.requestedChannel = channel;
        value.fallbackChannel = fallbackChannel;
        value.recipient = recipient;
        value.subject = subject;
        value.body = body;
        value.category = category == null ? Category.NOTIFICATION : category;
        value.quietHours = quietHours;
        value.status = Status.PENDING;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public Delivery inConversation(String id) {
        conversationId = id;
        return this;
    }

    public String conversationId() {
        return conversationId;
    }

    public boolean terminal() {
        return status == Status.SENT || status == Status.FAILED;
    }

    /** Transactional messages (acknowledgements, invitations, booking links) are time-sensitive and ignore quiet hours. */
    public Instant quietUntil(Instant now) {
        if (category == Category.TRANSACTIONAL || quietHours == null) return null;
        return quietHours.endIfInside(now);
    }

    public void defer(Instant until, String why, Instant now) {
        requireOpen();
        status = Status.DEFERRED;
        dueAt = until;
        reason = why;
        record("DEFERRED", requestedChannel, why, now);
    }

    public void resume(Instant now) {
        if (status == Status.DEFERRED) {
            status = Status.PENDING;
            dueAt = null;
            updatedAt = now;
        }
    }

    public void attempt(Instant now) {
        requireOpen();
        attempts++;
        updatedAt = now;
    }

    public void sent(String channel, String note, Instant now) {
        requireOpen();
        status = Status.SENT;
        channelUsed = channel;
        reason = note;
        dueAt = null;
        erase();
        record("SENT", channel, note, now);
    }

    public void failed(String why, Instant now) {
        if (terminal()) return;
        status = Status.FAILED;
        reason = why;
        dueAt = null;
        record("FAILED", channelUsed == null ? requestedChannel : channelUsed, why, now);
    }

    /** Re-queues a failed delivery for an operator retry. */
    public void retryRequested(Instant now) {
        if (status != Status.FAILED) throw new IllegalStateException("Only a failed delivery can be retried");
        if (body == null)
            throw new IllegalStateException(
                    "The content was purged after the retention period; send the message again");
        status = Status.PENDING;
        reason = null;
        updatedAt = now;
    }

    private void erase() {
        subject = null;
        body = null;
    }

    private void requireOpen() {
        if (terminal()) throw new IllegalStateException("Delivery " + id + " is already " + status);
    }

    private void record(String outcome, String channel, String why, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("messageId", id);
        payload.put("status", outcome);
        if (channel != null) payload.put("channel", channel);
        if (why != null) payload.put("reason", why);
        payload.put("attempts", attempts);
        events.add(new PendingEvent(
                UUID.randomUUID().toString(),
                "notification-dispatcher",
                "communication.delivery.updated.v1",
                1,
                now.toString(),
                tenantId,
                "Delivery",
                id,
                id,
                payload));
        updatedAt = now;
    }

    public Duration age(Instant now) {
        return Duration.between(createdAt, now);
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String audience() {
        return audience;
    }

    public String requestedChannel() {
        return requestedChannel;
    }

    public String fallbackChannel() {
        return fallbackChannel;
    }

    public String channelUsed() {
        return channelUsed;
    }

    public Recipient recipient() {
        return recipient;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public Category category() {
        return category;
    }

    public Status status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public int attempts() {
        return attempts;
    }

    public Instant dueAt() {
        return dueAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<PendingEvent> pendingEvents() {
        return List.copyOf(events);
    }
}
