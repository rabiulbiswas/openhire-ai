package com.openhire.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.dispatch.domain.Delivery;
import com.openhire.dispatch.service.port.ChannelAdapter;
import com.openhire.dispatch.service.port.DispatchPorts.DeliveryStore;
import com.openhire.dispatch.service.port.DispatchPorts.SendLock;
import com.openhire.dispatch.service.port.DispatchPorts.Throttle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DispatchServiceTest {
    private final Instant now = Instant.parse("2026-10-12T06:00:00Z"); // 11:30 in Kolkata
    private final Clock clock = Clock.fixed(now, ZoneId.of("UTC"));
    private final Store store = new Store();
    private final Email email = new Email();
    private final Set<String> locked = new HashSet<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private int perHour = 30;

    private final Throttle throttle = (tenant, key, limit) -> counts.merge(key, 1, Integer::sum) <= limit;
    private final SendLock locks = new SendLock() {
        public boolean acquire(String messageId, Duration lease) {
            return locked.add(messageId);
        }

        public void release(String messageId) {
            locked.remove(messageId);
        }
    };

    private DispatchService service() {
        return new DispatchService(
                store,
                List.of(email, new Off("SMS"), new Off("WHATSAPP")),
                throttle,
                locks,
                new DispatchService.Settings(perHour, Duration.ofMinutes(15), 3, Duration.ofDays(7)),
                clock);
    }

    private Delivery request(
            String id,
            String channel,
            String fallback,
            Delivery.Category category,
            Delivery.QuietHours quiet,
            String address) {
        return Delivery.request(
                id,
                "t1",
                "CANDIDATE",
                channel,
                fallback,
                new Delivery.Recipient("Priya", address, null),
                "Subject",
                "Body",
                category,
                quiet,
                now);
    }

    @Test
    void sendsOnceEvenWhenTheCommandIsRedelivered() {
        var service = service();
        assertThat(service.accept(request("m1", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isEqualTo(DispatchService.Outcome.SENT);
        assertThat(service.accept(request("m1", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isEqualTo(DispatchService.Outcome.SKIPPED);

        assertThat(email.sent).containsExactly("p@x.test");
        assertThat(store.rows.get("m1").status()).isEqualTo(Delivery.Status.SENT);
    }

    @Test
    void anUnconfiguredChannelFallsBackToEmailAndSaysSo() {
        service().accept(request("m2", "WHATSAPP", "EMAIL", Delivery.Category.TRANSACTIONAL, null, "p@x.test"));

        var delivery = store.rows.get("m2");
        assertThat(delivery.channelUsed()).isEqualTo("EMAIL");
        assertThat(delivery.reason()).isEqualTo("WHATSAPP is not configured; sent by email");
    }

    @Test
    void anUnconfiguredChannelWithoutFallbackFailsVisibly() {
        assertThat(service().accept(request("m3", "SMS", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isEqualTo(DispatchService.Outcome.FAILED);
        assertThat(store.rows.get("m3").reason()).startsWith("CHANNEL_NOT_CONFIGURED");
        assertThat(service().list("t1", Delivery.Status.FAILED, 10))
                .extracting(DispatchService.DeliveryView::messageId)
                .containsExactly("m3");
    }

    @Test
    void notificationsInsideQuietHoursAreDeferredAndReleasedLater() {
        var quiet = new Delivery.QuietHours("11:00", "13:00", "Asia/Kolkata");
        assertThat(service().accept(request("m4", "EMAIL", null, Delivery.Category.NOTIFICATION, quiet, "s@x.test")))
                .isEqualTo(DispatchService.Outcome.DEFERRED);
        assertThat(store.rows.get("m4").dueAt()).isEqualTo(Instant.parse("2026-10-12T07:30:00Z"));
        assertThat(email.sent).isEmpty();

        var later = new DispatchService(
                store,
                List.of(email, new Off("SMS"), new Off("WHATSAPP")),
                throttle,
                locks,
                new DispatchService.Settings(perHour, Duration.ofMinutes(15), 3, Duration.ofDays(7)),
                Clock.fixed(Instant.parse("2026-10-12T07:31:00Z"), ZoneId.of("UTC")));
        assertThat(later.releaseDue()).isEqualTo(1);
        assertThat(store.rows.get("m4").status()).isEqualTo(Delivery.Status.SENT);
    }

    @Test
    void aBurstOfNotificationsToOneRecipientIsThrottledButTransactionalMessagesAreNot() {
        perHour = 2;
        var service = service();
        for (int i = 0; i < 3; i++)
            service.accept(request("n" + i, "EMAIL", null, Delivery.Category.NOTIFICATION, null, "s@x.test"));
        service.accept(request("t9", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "s@x.test"));

        assertThat(store.rows.get("n2").status()).isEqualTo(Delivery.Status.DEFERRED);
        assertThat(store.rows.get("n2").reason()).isEqualTo("THROTTLED");
        assertThat(store.rows.get("t9").status()).isEqualTo(Delivery.Status.SENT);
    }

    @Test
    void transientFailuresGoToTheRetryTopicsAndExhaustionMarksTheDeliveryFailed() {
        email.down = true;
        var service = service();

        assertThatThrownBy(() ->
                        service.accept(request("m5", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isInstanceOf(ChannelAdapter.TransientFailure.class);
        assertThatThrownBy(() ->
                        service.accept(request("m5", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isInstanceOf(ChannelAdapter.TransientFailure.class);
        assertThat(store.rows.get("m5").attempts()).isEqualTo(2);

        service.exhausted("m5", "SMTP unavailable");
        assertThat(store.rows.get("m5").status()).isEqualTo(Delivery.Status.FAILED);

        email.down = false;
        assertThat(service.retry("t1", "m5").status()).isEqualTo("SENT");
        assertThat(email.sent).containsExactly("p@x.test");
    }

    @Test
    void scheduledRetriesBackOffAndGiveUpAfterTheLimit() {
        email.down = true;
        var service = service();
        store.insertOrGet(request("m6", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test"));

        assertThat(service.process("m6", false)).isEqualTo(DispatchService.Outcome.DEFERRED);
        assertThat(store.rows.get("m6").reason()).startsWith("RETRYING");
        store.rows.get("m6").resume(now);
        assertThat(service.process("m6", false)).isEqualTo(DispatchService.Outcome.DEFERRED);
        store.rows.get("m6").resume(now);
        assertThat(service.process("m6", false)).isEqualTo(DispatchService.Outcome.FAILED);
    }

    @Test
    void aMissingAddressIsAPermanentFailure() {
        assertThat(service().accept(request("m7", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, null)))
                .isEqualTo(DispatchService.Outcome.FAILED);
        assertThat(store.rows.get("m7").reason()).startsWith("NO_ADDRESS");
    }

    @Test
    void aMessageBeingSentElsewhereIsSkipped() {
        locked.add("m8");
        assertThat(service().accept(request("m8", "EMAIL", null, Delivery.Category.TRANSACTIONAL, null, "p@x.test")))
                .isEqualTo(DispatchService.Outcome.SKIPPED);
        assertThat(email.sent).isEmpty();
    }

    static final class Email extends ChannelAdapter {
        final List<String> sent = new ArrayList<>();
        boolean down;

        public String channel() {
            return "EMAIL";
        }

        public boolean configured() {
            return true;
        }

        protected String address(Delivery.Recipient recipient) {
            return recipient.email();
        }

        protected void send(Delivery delivery, String address) {
            if (down) throw new IllegalStateException("connection refused");
            sent.add(address);
        }
    }

    static final class Off extends ChannelAdapter {
        private final String channel;

        Off(String channel) {
            this.channel = channel;
        }

        public String channel() {
            return channel;
        }

        public boolean configured() {
            return false;
        }

        protected String address(Delivery.Recipient recipient) {
            return recipient.phone();
        }

        protected void send(Delivery delivery, String address) {
            throw new PermanentFailure("off");
        }
    }

    static final class Store implements DeliveryStore {
        final Map<String, Delivery> rows = new HashMap<>();

        public Optional<Delivery> find(String messageId) {
            return Optional.ofNullable(rows.get(messageId));
        }

        public Delivery insertOrGet(Delivery delivery) {
            return rows.computeIfAbsent(delivery.id(), id -> delivery);
        }

        public Delivery save(Delivery delivery) {
            rows.put(delivery.id(), delivery);
            return delivery;
        }

        public List<Delivery> due(Instant at, int limit) {
            return rows.values().stream()
                    .filter(d ->
                            d.status() == Delivery.Status.DEFERRED && !d.dueAt().isAfter(at))
                    .toList();
        }

        public List<Delivery> list(String tenantId, Delivery.Status status, int limit) {
            return rows.values().stream()
                    .filter(d -> status == null || d.status() == status)
                    .toList();
        }

        public long purgeFailedContent(Instant before) {
            return 0;
        }
    }
}
