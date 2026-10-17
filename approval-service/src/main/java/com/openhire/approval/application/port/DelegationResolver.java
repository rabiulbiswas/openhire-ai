package com.openhire.approval.application.port;

import java.time.Instant;

public interface DelegationResolver {
    String resolve(String tenantId, String ownerId, Instant at);
}
