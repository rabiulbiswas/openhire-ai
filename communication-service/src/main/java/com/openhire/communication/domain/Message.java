package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.Audience;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.Direction;
import com.openhire.communication.domain.CommunicationTypes.MessageStatus;
import com.openhire.communication.domain.CommunicationTypes.PendingEvent;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.domain.CommunicationTypes.Recipient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * One message in either direction. An outbound message carries its dispatch command in the same
 * document, so recording the message and requesting delivery commit atomically.
 */
@Document("messages")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_conversation", def = "{'tenantId': 1, 'conversationId': 1, 'createdAt': 1}"),
    @CompoundIndex(
            name = "trigger_dedupe_v2",
            def = "{'tenantId': 1, 'dedupeKey': 1}",
            unique = true,
            partialFilter = "{'dedupeKey': {'$exists': true}}")
})
public class Message {
    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String conversationId;
    private Audience audience;
    private Direction direction;
    private Channel channel;
    private Recipient recipient;
    private String subject;
    private String body;
    private String templateKey;
    private Integer templateVersion;
    private MessageStatus status;
    private String statusReason;
    private String actorId;
    private String dedupeKey;
    private Instant createdAt;
    private Instant updatedAt;

    @Field("_events")
    private List<PendingEvent> events = new ArrayList<>();

    protected Message() {}

    public static Message outbound(
            String tenantId,
            String conversationId,
            Audience audience,
            Channel channel,
            Recipient recipient,
            String subject,
            String body,
            String templateKey,
            Integer templateVersion,
            String actorId,
            String dedupeKey,
            boolean transactional,
            QuietHours quietHours,
            Instant now) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("A message needs text");
        if (body.length() > 5000) throw new IllegalArgumentException("Messages are limited to 5,000 characters");
        var message = new Message();
        message.id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        message.tenantId = tenantId;
        message.conversationId = conversationId;
        message.audience = audience;
        message.direction = Direction.OUTBOUND;
        message.channel = channel;
        message.recipient = recipient;
        message.subject = subject;
        message.body = body.trim();
        message.templateKey = templateKey;
        message.templateVersion = templateVersion;
        message.status = MessageStatus.QUEUED;
        message.actorId = actorId;
        message.dedupeKey = dedupeKey;
        message.createdAt = now;
        message.updatedAt = now;
        var command = new LinkedHashMap<String, Object>();
        command.put("messageId", message.id);
        command.put("audience", audience.name());
        command.put("channel", channel.name());
        command.put("fallbackChannel", channel == Channel.EMAIL ? null : Channel.EMAIL.name());
        var to = new LinkedHashMap<String, Object>();
        to.put("name", recipient.name());
        if (recipient.email() != null) to.put("email", recipient.email());
        if (recipient.phone() != null) to.put("phone", recipient.phone());
        command.put("recipient", to);
        if (subject != null) command.put("subject", subject);
        command.put("body", message.body);
        command.put("category", transactional ? "TRANSACTIONAL" : "NOTIFICATION");
        if (quietHours != null && quietHours.enabled()) {
            var quiet = new LinkedHashMap<String, Object>();
            quiet.put("from", quietHours.from());
            quiet.put("to", quietHours.to());
            quiet.put("timezone", quietHours.timezone() == null ? "UTC" : quietHours.timezone());
            command.put("quietHours", quiet);
        }
        command.values().removeIf(java.util.Objects::isNull);
        message.events.add(PendingEvent.of(
                "communication.message.requested.v1",
                tenantId,
                "Message",
                message.id,
                conversationId == null ? message.id : conversationId,
                command,
                now));
        return message;
    }

    public static Message inbound(String tenantId, String conversationId, Recipient from, String body, Instant now) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("A message needs text");
        if (body.length() > 5000) throw new IllegalArgumentException("Messages are limited to 5,000 characters");
        var message = new Message();
        message.id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        message.tenantId = tenantId;
        message.conversationId = conversationId;
        message.audience = Audience.CANDIDATE;
        message.direction = Direction.INBOUND;
        message.channel = Channel.EMAIL;
        message.recipient = from;
        message.body = body.trim();
        message.status = MessageStatus.RECEIVED;
        message.createdAt = now;
        message.updatedAt = now;
        return message;
    }

    /** Applies a delivery update unless it would move the message backwards (updates may arrive out of order). */
    public boolean applyDelivery(MessageStatus next, String reason, Instant at) {
        if (direction != Direction.OUTBOUND) return false;
        if (status == MessageStatus.FAILED || status == MessageStatus.DELIVERED) return false;
        if (next.rank() < status.rank()) return false;
        status = next;
        statusReason = reason;
        updatedAt = at;
        return true;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String conversationId() {
        return conversationId;
    }

    public Audience audience() {
        return audience;
    }

    public Direction direction() {
        return direction;
    }

    public Channel channel() {
        return channel;
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

    public String templateKey() {
        return templateKey;
    }

    public MessageStatus status() {
        return status;
    }

    public String statusReason() {
        return statusReason;
    }

    public String actorId() {
        return actorId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PendingEvent> pendingEvents() {
        return List.copyOf(events);
    }
}
