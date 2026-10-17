package com.openhire.jobservice.application.port;

public interface ApprovalClient {
    String requestJobApproval(String tenantId, String actorId, String jobId, String title, int version);
}
