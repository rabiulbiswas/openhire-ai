package com.openhire.jobservice.service.port;

import java.util.List;

public interface ApprovalClient {
    String requestJobApproval(
            String tenantId, String actorId, String jobId, String title, int version, List<Term> terms);

    default String requestAssignedJobApproval(
            String tenantId,
            String actorId,
            String jobId,
            String title,
            int version,
            List<Term> terms,
            String approver) {
        throw new IllegalStateException("Named approval assignment is required");
    }

    record Term(String label, String value) {}

    String requestJobChangeApproval(
            String tenantId, String actorId, String jobId, String title, Comparison comparison, List<FieldChange> diff);

    default String requestAssignedJobChangeApproval(
            String tenantId,
            String actorId,
            String jobId,
            String title,
            Comparison comparison,
            List<FieldChange> diff,
            String approver) {
        throw new IllegalStateException("Named approval assignment is required");
    }

    record Comparison(int previousVersion, int proposedVersion) {}

    record FieldChange(String field, String before, String after) {}
}
