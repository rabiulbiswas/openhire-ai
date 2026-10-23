package com.openhire.approval.service.port;

import java.util.Map;

public interface ApprovalEventPublisher {
    void publish(String type, String tenantId, String approvalId, Map<String, Object> payload);
}
