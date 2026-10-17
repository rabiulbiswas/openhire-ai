package com.openhire.jobservice.application.port;

import com.openhire.platform.event.DomainEventEnvelope;

public interface OutboxWriter {
    void append(String topic, DomainEventEnvelope event);
}
