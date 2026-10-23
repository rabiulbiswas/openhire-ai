package com.openhire.jobservice.service.port;

public interface ApprovalClient {
    String requestJobApproval(String tenantId, String actorId, String jobId, String title, int version);
}
