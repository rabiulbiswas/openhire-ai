package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * In-app notification. Recipients are keys: "user:{subject}", "email:{address}" or "role:{ROLE}", so a
 * notification can reach an interviewer known only by email before they ever sign in.
 */
@Document("notifications")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_recipient_created", def = "{'tenantId': 1, 'recipient': 1, 'createdAt': -1}"),
    @CompoundIndex(
            name = "dedupe_v2",
            def = "{'tenantId': 1, 'dedupeKey': 1}",
            unique = true,
            partialFilter = "{'dedupeKey': {'$exists': true}}")
})
public class StaffNotification {
    @Id
    private String id;

    private String tenantId;
    private String recipient;
    private String type;
    private NotificationEventKey eventKey;
    private String title;
    private String body;
    private String route;
    private Map<String, String> params;
    private boolean mention;
    private String dedupeKey;
    private Instant createdAt;
    private Instant readAt;

    protected StaffNotification() {}

    public static StaffNotification create(
            String tenantId,
            String recipient,
            String type,
            NotificationEventKey eventKey,
            String title,
            String body,
            String route,
            Map<String, String> params,
            boolean mention,
            String dedupeKey,
            Instant now) {
        var value = new StaffNotification();
        value.id = "ntf_" + UUID.randomUUID().toString().replace("-", "");
        value.tenantId = tenantId;
        value.recipient = recipient;
        value.type = type;
        value.eventKey = eventKey;
        value.title = title;
        value.body = body;
        value.route = route;
        value.params = params == null ? Map.of() : Map.copyOf(params);
        value.mention = mention;
        value.dedupeKey = dedupeKey;
        value.createdAt = now;
        return value;
    }

    public static List<String> recipientKeys(String subject, String email, java.util.Collection<String> roles) {
        var keys = new java.util.ArrayList<String>();
        if (subject != null) keys.add("user:" + subject);
        if (email != null && !email.isBlank()) keys.add("email:" + email.toLowerCase(java.util.Locale.ROOT));
        roles.forEach(role -> keys.add("role:" + role));
        return keys;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String recipient() {
        return recipient;
    }

    public String type() {
        return type;
    }

    public NotificationEventKey eventKey() {
        return eventKey;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public String route() {
        return route;
    }

    public Map<String, String> params() {
        return params;
    }

    public boolean mention() {
        return mention;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant readAt() {
        return readAt;
    }
}
