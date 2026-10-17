package com.openhire.platform.event;

import java.time.Duration;

public interface ProcessedEventStore {
    boolean claim(String tenantId, String consumer, String eventId, Duration retention);
}
