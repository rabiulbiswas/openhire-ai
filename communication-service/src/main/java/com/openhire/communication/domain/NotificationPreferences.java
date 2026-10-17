package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.DeliveryChannel;
import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** A staff member's channel matrix, quiet hours and email digest choice. */
@Document("notification_preferences")
@CompoundIndex(name = "tenant_email", def = "{'tenantId': 1, 'email': 1}")
public class NotificationPreferences {
    public enum Digest {
        IMMEDIATE,
        HOURLY,
        DAILY_9AM
    }

    private static final Set<NotificationEventKey> EMAIL_BY_DEFAULT = Set.of(
            NotificationEventKey.APPROVAL_REQUESTED,
            NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED,
            NotificationEventKey.SCORECARD_OVERDUE,
            NotificationEventKey.CANDIDATE_REPLIED,
            NotificationEventKey.ASSIGNED_TO_JOB_ROUND,
            NotificationEventKey.WEEKLY_SUMMARY);

    @Id
    private String id;

    private String tenantId;
    private String subject;
    private String email;
    private String timezone;
    private Map<NotificationEventKey, Map<DeliveryChannel, Boolean>> matrix;
    private QuietHours quietHours;
    private Digest emailDigest;
    private Instant lastDigestAt;
    private Instant updatedAt;

    protected NotificationPreferences() {}

    public static String idFor(String tenantId, String subject) {
        return tenantId + ":" + subject;
    }

    public static NotificationPreferences defaults(String tenantId, String subject, String email, String timezone) {
        var value = new NotificationPreferences();
        value.id = idFor(tenantId, subject);
        value.tenantId = tenantId;
        value.subject = subject;
        value.email = email == null ? null : email.toLowerCase(Locale.ROOT);
        value.timezone = timezone;
        value.matrix = new EnumMap<>(NotificationEventKey.class);
        for (var key : NotificationEventKey.values()) {
            var row = new EnumMap<DeliveryChannel, Boolean>(DeliveryChannel.class);
            row.put(DeliveryChannel.IN_APP, true);
            row.put(DeliveryChannel.EMAIL, EMAIL_BY_DEFAULT.contains(key));
            row.put(DeliveryChannel.SLACK, false);
            row.put(DeliveryChannel.WHATSAPP, false);
            value.matrix.put(key, row);
        }
        value.quietHours = QuietHours.off();
        value.emailDigest = Digest.IMMEDIATE;
        return value;
    }

    public void update(
            Map<NotificationEventKey, Map<DeliveryChannel, Boolean>> nextMatrix,
            QuietHours nextQuiet,
            Digest digest,
            Instant now) {
        for (var entry : nextMatrix.entrySet()) {
            var row = new EnumMap<DeliveryChannel, Boolean>(DeliveryChannel.class);
            for (var channel : DeliveryChannel.values())
                row.put(channel, Boolean.TRUE.equals(entry.getValue().get(channel)));
            matrix.put(entry.getKey(), row);
        }
        quietHours = nextQuiet == null ? QuietHours.off() : nextQuiet;
        emailDigest = digest == null ? Digest.IMMEDIATE : digest;
        updatedAt = now;
    }

    /** Signing in keeps the stored address current, so email notifications reach the right inbox. */
    public boolean refreshContact(String nextEmail) {
        if (nextEmail == null || nextEmail.isBlank()) return false;
        var normalized = nextEmail.toLowerCase(Locale.ROOT);
        if (normalized.equals(email)) return false;
        email = normalized;
        return true;
    }

    public boolean allows(NotificationEventKey key, DeliveryChannel channel) {
        var row = matrix.get(key);
        return row != null && Boolean.TRUE.equals(row.get(channel));
    }

    public boolean digestDue(Instant now) {
        var zone = zone();
        return switch (emailDigest) {
            case IMMEDIATE -> false;
            case HOURLY -> lastDigestAt == null || !lastDigestAt.isAfter(now.minusSeconds(3600));
            case DAILY_9AM -> {
                var local = ZonedDateTime.ofInstant(now, zone);
                var nine = local.toLocalDate()
                        .atTime(LocalTime.of(9, 0))
                        .atZone(zone)
                        .toInstant();
                yield !now.isBefore(nine) && (lastDigestAt == null || lastDigestAt.isBefore(nine));
            }
        };
    }

    public void markDigestSent(Instant now) {
        lastDigestAt = now;
    }

    private ZoneId zone() {
        if (quietHours != null && quietHours.timezone() != null) return ZoneId.of(quietHours.timezone());
        return timezone == null ? ZoneId.of("UTC") : ZoneId.of(timezone);
    }

    public QuietHours effectiveQuietHours() {
        if (quietHours == null || !quietHours.enabled()) return QuietHours.off();
        return new QuietHours(true, quietHours.from(), quietHours.to(), zone().getId());
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String subject() {
        return subject;
    }

    public String email() {
        return email;
    }

    public Map<NotificationEventKey, Map<DeliveryChannel, Boolean>> matrix() {
        return matrix;
    }

    public QuietHours quietHours() {
        return quietHours;
    }

    public Digest emailDigest() {
        return emailDigest;
    }
}
