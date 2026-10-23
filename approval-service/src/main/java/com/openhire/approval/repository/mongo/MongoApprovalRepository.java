package com.openhire.approval.repository.mongo;

import com.openhire.approval.domain.ApprovalRequest;
import com.openhire.approval.service.port.ApprovalRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MongoApprovalRepository implements ApprovalRepository {
    private final SpringDataApprovalRepository delegate;

    public MongoApprovalRepository(SpringDataApprovalRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public ApprovalRequest save(ApprovalRequest request) {
        return delegate.save(request);
    }

    @Override
    public Optional<ApprovalRequest> find(String tenantId, String id) {
        return delegate.findByIdAndTenantId(id, tenantId);
    }

    @Override
    public List<ApprovalRequest> findAll(String tenantId) {
        return delegate.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Override
    public List<ApprovalRequest> findAllTenants() {
        return delegate.findAll();
    }
}

interface SpringDataApprovalRepository extends MongoRepository<ApprovalRequest, String> {
    Optional<ApprovalRequest> findByIdAndTenantId(String id, String tenantId);

    List<ApprovalRequest> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
