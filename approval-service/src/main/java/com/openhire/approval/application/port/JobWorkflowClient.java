package com.openhire.approval.application.port;

public interface JobWorkflowClient {
    void decision(String tenantId, String jobId, String decision, String reason, String actorId);
}
