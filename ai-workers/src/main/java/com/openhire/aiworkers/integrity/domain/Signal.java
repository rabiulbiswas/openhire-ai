package com.openhire.aiworkers.integrity.domain;

import java.time.Instant;

public record Signal(
        String id,
        String tenantId,
        String applicationId,
        String candidateId,
        String resumeId,
        String type,
        String severity,
        double confidence,
        String evidence,
        String status,
        Instant createdAt,
        String dismissedBy,
        String dismissReason,
        Instant dismissedAt) {
    public static final String RAISED = "RAISED";
    public static final String DISMISSED = "DISMISSED";
}
