package com.openhire.platform.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotentEventHandlerTest {
    @Test
    void processesAnEventOnlyOncePerTenantAndConsumer() {
        var claims = new HashSet<String>();
        ProcessedEventStore store =
                (tenant, consumer, event, retention) -> claims.add(tenant + ":" + consumer + ":" + event);
        var handler = new IdempotentEventHandler(store, Duration.ofDays(30));
        var invocations = new AtomicInteger();

        assertThat(handler.handle(
                        "tenant-a", "projection", "event-1", "payload", ignored -> invocations.incrementAndGet()))
                .isTrue();
        assertThat(handler.handle(
                        "tenant-a", "projection", "event-1", "payload", ignored -> invocations.incrementAndGet()))
                .isFalse();
        assertThat(invocations).hasValue(1);
    }
}
