package com.openhire.jobservice.application.port;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {
    Optional<String> findResourceId(String tenantId, String key);

    void remember(String tenantId, String key, String resourceId, Duration ttl);
}
