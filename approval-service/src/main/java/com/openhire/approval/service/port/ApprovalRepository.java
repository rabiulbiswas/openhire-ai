package com.openhire.approval.service.port;

import com.openhire.approval.domain.ApprovalRequest;
import java.util.List;
import java.util.Optional;

public interface ApprovalRepository {
    ApprovalRequest save(ApprovalRequest request);

    Optional<ApprovalRequest> find(String tenantId, String id);

    List<ApprovalRequest> findAll(String tenantId);

    List<ApprovalRequest> findAllTenants();
}
