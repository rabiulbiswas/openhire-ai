package com.openhire.identity.service.port;

import java.util.Map;

public interface IdentityEventPublisher {
    void publish(String eventType, String organizationId, String aggregateId, Map<String, Object> payload);
}
