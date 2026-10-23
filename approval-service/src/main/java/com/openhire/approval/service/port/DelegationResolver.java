package com.openhire.approval.service.port;

import java.time.Instant;

public interface DelegationResolver {
    String resolve(String tenantId, String ownerId, Instant at);
}
