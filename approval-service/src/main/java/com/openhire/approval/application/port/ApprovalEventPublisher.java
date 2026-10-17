package com.openhire.approval.application.port;

import java.util.Map;

public interface ApprovalEventPublisher {
    void publish(String type, String tenantId, String approvalId, Map<String, Object> payload);
}
