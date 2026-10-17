package com.openhire.communication.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

public final class CommunicationTypes {
    private CommunicationTypes() {}

    public enum Channel {
        EMAIL,
        SMS,
        WHATSAPP
    }

    public enum Audience {
        CANDIDATE,
        STAFF
    }

    public enum Direction {
        OUTBOUND,
        INBOUND
    }

    /** Delivery lifecycle reported by the dispatcher; RECEIVED is an inbound candidate reply. */
    public enum MessageStatus {
        QUEUED(0),
        DEFERRED(1),
        SENT(2),
        DELIVERED(3),
        FAILED(3),
        RECEIVED(3);

        private final int rank;

        MessageStatus(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public record Recipient(String name, String email, String phone) {}

    public record QuietHours(boolean enabled, String from, String to, String timezone) {
        public QuietHours {
            if (enabled) {
                LocalTime.parse(from);
                LocalTime.parse(to);
            }
            if (timezone != null && !timezone.isBlank()) ZoneId.of(timezone);
        }

        public static QuietHours off() {
            return new QuietHours(false, "20:00", "08:00", null);
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
            Map<String, Object> payload) {
        public static PendingEvent of(
                String type,
                String tenantId,
                String aggregateType,
                String aggregateId,
                String correlationId,
                Map<String, Object> payload,
                Instant now) {
            return new PendingEvent(
                    java.util.UUID.randomUUID().toString(),
                    "communication-service",
                    type,
                    1,
                    now.toString(),
                    tenantId,
                    aggregateType,
                    aggregateId,
                    correlationId,
                    payload);
        }
    }

    /** Console notification event keys (preferences matrix rows). */
    public enum NotificationEventKey {
        NEW_APPLICATIONS_SCORED,
        APPROVAL_REQUESTED,
        INTERVIEW_SCHEDULED_CHANGED,
        SCORECARD_OVERDUE,
        CANDIDATE_REPLIED,
        OFFER_ACCEPTED_DECLINED,
        ASSIGNED_TO_JOB_ROUND,
        WEEKLY_SUMMARY
    }

    public enum DeliveryChannel {
        IN_APP,
        EMAIL,
        SLACK,
        WHATSAPP
    }
}
