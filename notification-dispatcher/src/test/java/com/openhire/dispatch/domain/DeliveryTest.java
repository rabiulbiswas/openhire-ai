package com.openhire.dispatch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeliveryTest {
    static final Instant NOW = Instant.parse("2026-10-12T17:00:00Z"); // 22:30 in Kolkata

    static Delivery delivery(Delivery.Category category, Delivery.QuietHours quiet) {
        return Delivery.request(
                "msg-1",
                "t1",
                "CANDIDATE",
                "EMAIL",
                null,
                new Delivery.Recipient("Priya", "p@x.test", null),
                "Subject",
                "Body",
                category,
                quiet,
                NOW);
    }

    @Test
    void overnightQuietHoursEndTheNextMorningInTheRecipientsZone() {
        var quiet = new Delivery.QuietHours("21:00", "08:00", "Asia/Kolkata");

        assertThat(quiet.endIfInside(NOW)).isEqualTo(Instant.parse("2026-10-13T02:30:00Z"));
        assertThat(quiet.endIfInside(Instant.parse("2026-10-12T22:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-13T02:30:00Z"));
        assertThat(quiet.endIfInside(Instant.parse("2026-10-12T06:00:00Z"))).isNull();
    }

    @Test
    void sameDayQuietHoursWork() {
        var lunch = new Delivery.QuietHours("12:00", "13:00", "UTC");

        assertThat(lunch.endIfInside(Instant.parse("2026-10-12T12:30:00Z")))
                .isEqualTo(Instant.parse("2026-10-12T13:00:00Z"));
        assertThat(lunch.endIfInside(Instant.parse("2026-10-12T13:00:00Z"))).isNull();
    }

    @Test
    void transactionalMessagesIgnoreQuietHours() {
        var quiet = new Delivery.QuietHours("21:00", "08:00", "Asia/Kolkata");

        assertThat(delivery(Delivery.Category.TRANSACTIONAL, quiet).quietUntil(NOW))
                .isNull();
        assertThat(delivery(Delivery.Category.NOTIFICATION, quiet).quietUntil(NOW))
                .isNotNull();
    }

    @Test
    void sentDeliveriesEraseTheirContentAndCannotChangeAgain() {
        var value = delivery(Delivery.Category.TRANSACTIONAL, null);
        value.attempt(NOW);
        value.sent("EMAIL", null, NOW);

        assertThat(value.body()).isNull();
        assertThat(value.terminal()).isTrue();
        assertThatThrownBy(() -> value.defer(NOW, "x", NOW)).isInstanceOf(IllegalStateException.class);
        value.failed("late failure", NOW);
        assertThat(value.status()).isEqualTo(Delivery.Status.SENT);
        assertThat(value.pendingEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("communication.delivery.updated.v1");
            assertThat(event.payload()).containsEntry("status", "SENT").doesNotContainKey("body");
        });
    }

    @Test
    void failedDeliveriesKeepContentForAnOperatorRetry() {
        var value = delivery(Delivery.Category.NOTIFICATION, null);
        value.failed("SMTP unavailable", NOW);

        assertThat(value.body()).isEqualTo("Body");
        value.retryRequested(NOW);
        assertThat(value.status()).isEqualTo(Delivery.Status.PENDING);
    }
}
