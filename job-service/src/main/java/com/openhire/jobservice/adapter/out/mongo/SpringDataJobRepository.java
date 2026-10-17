package com.openhire.jobservice.adapter.out.mongo;

import com.openhire.jobservice.domain.Job;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

interface SpringDataJobRepository extends MongoRepository<Job, String> {
    Optional<Job> findByIdAndTenantId(String id, String tenantId);

    List<Job> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
}
