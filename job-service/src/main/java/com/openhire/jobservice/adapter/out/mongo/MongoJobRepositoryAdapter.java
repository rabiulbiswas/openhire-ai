package com.openhire.jobservice.adapter.out.mongo;

import com.openhire.jobservice.application.port.JobRepository;
import com.openhire.jobservice.domain.Job;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MongoJobRepositoryAdapter implements JobRepository {
    private final SpringDataJobRepository repository;

    public MongoJobRepositoryAdapter(SpringDataJobRepository repository) {
        this.repository = repository;
    }

    @Override
    public Job save(Job job) {
        return repository.save(job);
    }

    @Override
    public Optional<Job> findById(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId);
    }

    @Override
    public List<Job> findAll(String tenantId) {
        return repository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }
}
