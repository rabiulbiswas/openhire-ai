package com.openhire.approval.service.port;

public interface JobWorkflowClient {
    void decision(String tenantId, String jobId, String decision, String reason, String actorId);

    void decision(String tenantId, String jobId, String decision, String reason, String actorId, int expectedVersion);
}
