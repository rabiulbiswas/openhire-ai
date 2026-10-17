package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** Thread summary per candidate; rebuilt from messages, so it is a projection rather than the source of truth. */
@Document("conversations")
@CompoundIndex(name = "tenant_recent", def = "{'tenantId': 1, 'lastMessageAt': -1}")
public class Conversation {
    @Id
    private String id;

    private String tenantId;
    private String candidateId;
    private String candidateName;
    private String applicationId;
    private Channel channel;
    private String lastMessage;
    private Instant lastMessageAt;
    private boolean unread;
    private String ownerId;

    protected Conversation() {}

    public static String idFor(String tenantId, String candidateId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000" + candidateId).getBytes(StandardCharsets.UTF_8));
            return "conv_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Conversation start(
            String tenantId, String candidateId, String candidateName, String applicationId, Channel channel) {
        var value = new Conversation();
        value.id = idFor(tenantId, candidateId);
        value.tenantId = tenantId;
        value.candidateId = candidateId;
        value.candidateName = candidateName;
        value.applicationId = applicationId;
        value.channel = channel;
        return value;
    }

    public void recordOutbound(String text, Instant at, String actorId, Channel used) {
        lastMessage = text;
        lastMessageAt = at;
        channel = used;
        if (actorId != null) ownerId = actorId;
    }

    public void recordInbound(String text, Instant at) {
        lastMessage = text;
        lastMessageAt = at;
        unread = true;
    }

    public void markRead() {
        unread = false;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String candidateId() {
        return candidateId;
    }

    public String candidateName() {
        return candidateName;
    }

    public String applicationId() {
        return applicationId;
    }

    public Channel channel() {
        return channel;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public Instant lastMessageAt() {
        return lastMessageAt;
    }

    public boolean unread() {
        return unread;
    }

    public String ownerId() {
        return ownerId;
    }
}
