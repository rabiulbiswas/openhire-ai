package com.openhire.dispatch.service.port;

import com.openhire.dispatch.domain.Delivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class DispatchPorts {
    private DispatchPorts() {}

    public interface DeliveryStore {
        Optional<Delivery> find(String messageId);

        /** Inserts the delivery, or returns the existing one if the command was already accepted. */
        Delivery insertOrGet(Delivery delivery);

        Delivery save(Delivery delivery);

        List<Delivery> due(Instant now, int limit);

        List<Delivery> list(String tenantId, Delivery.Status status, int limit);

        long purgeFailedContent(Instant failedBefore);
    }

    /** Per-recipient rate limit so a burst of notifications cannot flood one inbox. */
    public interface Throttle {
        boolean allow(String tenantId, String recipientKey, int perHour);
    }

    /** Short lease so the Kafka consumer and the deferred-release scheduler never send the same message at once. */
    public interface SendLock {
        boolean acquire(String messageId, Duration lease);

        void release(String messageId);
    }
}
