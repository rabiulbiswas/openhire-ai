package com.openhire.platform.event;

import java.time.Instant;

public record OutboxMessage(
        String id, String topic, String aggregateId, DomainEventEnvelope event, Instant createdAt) {}
