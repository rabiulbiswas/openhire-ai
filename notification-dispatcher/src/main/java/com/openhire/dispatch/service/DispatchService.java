package com.openhire.dispatch.service;

import com.openhire.dispatch.domain.Delivery;
import com.openhire.dispatch.service.port.ChannelAdapter;
import com.openhire.dispatch.service.port.ChannelAdapter.PermanentFailure;
import com.openhire.dispatch.service.port.ChannelAdapter.TransientFailure;
import com.openhire.dispatch.service.port.DispatchPorts.DeliveryStore;
import com.openhire.dispatch.service.port.DispatchPorts.SendLock;
import com.openhire.dispatch.service.port.DispatchPorts.Throttle;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DispatchService {
    public enum Outcome {
        SENT,
        DEFERRED,
        FAILED,
        SKIPPED
    }

    public record Settings(
            int notificationsPerHour,
            Duration throttleDelay,
            int maxScheduledAttempts,
            Duration failedContentRetention) {}

    public record DeliveryView(
            String messageId,
            String tenantId,
            String audience,
            String requestedChannel,
            String channelUsed,
            String status,
            String reason,
            int attempts,
            String dueAt,
            String createdAt,
            String updatedAt) {}

    private final DeliveryStore deliveries;
    private final Map<String, ChannelAdapter> channels;
    private final Throttle throttle;
    private final SendLock locks;
    private final Settings settings;
    private final Clock clock;

    public DispatchService(
            DeliveryStore deliveries,
            List<ChannelAdapter> adapters,
            Throttle throttle,
            SendLock locks,
            Settings settings,
            Clock clock) {
        this.deliveries = deliveries;
        this.channels = adapters.stream().collect(Collectors.toMap(ChannelAdapter::channel, Function.identity()));
        this.throttle = throttle;
        this.locks = locks;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Accepts a dispatch command. Redelivered commands find the existing delivery and never send twice.
     * Throws {@link TransientFailure} so the Kafka retry topics can try again later.
     */
    public Outcome accept(Delivery requested) {
        var delivery = deliveries.insertOrGet(requested);
        if (delivery.terminal()) return Outcome.SKIPPED;
        return process(delivery.id(), true);
    }

    /** Releases deferred deliveries whose time has come; transient failures here back off instead of throwing. */
    public int releaseDue() {
        int released = 0;
        for (var delivery : deliveries.due(clock.instant(), 100)) {
            process(delivery.id(), false);
            released++;
        }
        return released;
    }

    /** Called when the last Kafka retry is exhausted. */
    public void exhausted(String messageId, String reason) {
        deliveries.find(messageId).ifPresent(delivery -> {
            delivery.failed("RETRIES_EXHAUSTED: " + reason, clock.instant());
            deliveries.save(delivery);
        });
    }

    public DeliveryView retry(String tenantId, String messageId) {
        var delivery = deliveries
                .find(messageId)
                .filter(value -> value.tenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("Delivery " + messageId + " was not found"));
        delivery.retryRequested(clock.instant());
        deliveries.save(delivery);
        process(messageId, false);
        return view(deliveries.find(messageId).orElseThrow());
    }

    public List<DeliveryView> list(String tenantId, Delivery.Status status, int limit) {
        return deliveries.list(tenantId, status, Math.min(Math.max(limit, 1), 200)).stream()
                .map(DispatchService::view)
                .toList();
    }

    public DeliveryView get(String tenantId, String messageId) {
        return deliveries
                .find(messageId)
                .filter(value -> value.tenantId().equals(tenantId))
                .map(DispatchService::view)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery " + messageId + " was not found"));
    }

    public long purgeExpiredContent() {
        return deliveries.purgeFailedContent(clock.instant().minus(settings.failedContentRetention()));
    }

    Outcome process(String messageId, boolean viaKafka) {
        if (!locks.acquire(messageId, Duration.ofMinutes(2))) return Outcome.SKIPPED;
        try {
            var delivery = deliveries.find(messageId).orElseThrow();
            var now = clock.instant();
            if (delivery.terminal()) return Outcome.SKIPPED;
            if (delivery.status() == Delivery.Status.DEFERRED
                    && delivery.dueAt() != null
                    && delivery.dueAt().isAfter(now)) return Outcome.DEFERRED;
            delivery.resume(now);

            var quietUntil = delivery.quietUntil(now);
            if (quietUntil != null) {
                delivery.defer(quietUntil, "QUIET_HOURS", now);
                deliveries.save(delivery);
                return Outcome.DEFERRED;
            }
            if (delivery.category() == Delivery.Category.NOTIFICATION
                    && !throttle.allow(delivery.tenantId(), recipientKey(delivery), settings.notificationsPerHour())) {
                delivery.defer(now.plus(settings.throttleDelay()), "THROTTLED", now);
                deliveries.save(delivery);
                return Outcome.DEFERRED;
            }

            var adapter = channels.get(delivery.requestedChannel());
            String note = null;
            if (adapter == null || !adapter.configured()) {
                var fallback = delivery.fallbackChannel() == null ? null : channels.get(delivery.fallbackChannel());
                if (fallback == null || !fallback.configured()) {
                    delivery.failed(
                            "CHANNEL_NOT_CONFIGURED: " + delivery.requestedChannel() + " delivery is not set up", now);
                    deliveries.save(delivery);
                    return Outcome.FAILED;
                }
                note = delivery.requestedChannel() + " is not configured; sent by "
                        + fallback.channel().toLowerCase(Locale.ROOT);
                adapter = fallback;
            }

            delivery.attempt(now);
            try {
                adapter.deliver(delivery);
            } catch (PermanentFailure permanent) {
                delivery.failed(permanent.getMessage(), clock.instant());
                deliveries.save(delivery);
                return Outcome.FAILED;
            } catch (TransientFailure transientFailure) {
                if (viaKafka) {
                    deliveries.save(delivery);
                    throw transientFailure;
                }
                if (delivery.attempts() >= settings.maxScheduledAttempts())
                    delivery.failed(transientFailure.getMessage(), clock.instant());
                else
                    delivery.defer(
                            clock.instant().plus(Duration.ofMinutes(delivery.attempts() * 5L)),
                            "RETRYING: " + transientFailure.getMessage(),
                            clock.instant());
                deliveries.save(delivery);
                return delivery.terminal() ? Outcome.FAILED : Outcome.DEFERRED;
            }
            delivery.sent(adapter.channel(), note, clock.instant());
            deliveries.save(delivery);
            return Outcome.SENT;
        } finally {
            locks.release(messageId);
        }
    }

    private static String recipientKey(Delivery delivery) {
        var recipient = delivery.recipient();
        if (recipient.email() != null) return "email:" + recipient.email().toLowerCase(Locale.ROOT);
        return "phone:" + recipient.phone();
    }

    static DeliveryView view(Delivery value) {
        return new DeliveryView(
                value.id(),
                value.tenantId(),
                value.audience(),
                value.requestedChannel(),
                value.channelUsed(),
                value.status().name(),
                value.reason(),
                value.attempts(),
                value.dueAt() == null ? null : value.dueAt().toString(),
                value.createdAt().toString(),
                value.updatedAt().toString());
    }
}
