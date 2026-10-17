package com.openhire.platform.event;

import java.time.Duration;
import java.util.function.Consumer;

public final class IdempotentEventHandler {
    private final ProcessedEventStore processedEvents;
    private final Duration retention;

    public IdempotentEventHandler(ProcessedEventStore processedEvents, Duration retention) {
        this.processedEvents = processedEvents;
        this.retention = retention;
    }

    public <T> boolean handle(String tenantId, String consumer, String eventId, T payload, Consumer<T> action) {
        if (!processedEvents.claim(tenantId, consumer, eventId, retention)) return false;
        action.accept(payload);
        return true;
    }
}
