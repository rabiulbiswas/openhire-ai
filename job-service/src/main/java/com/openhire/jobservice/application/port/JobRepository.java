package com.openhire.jobservice.application.port;

import com.openhire.jobservice.domain.Job;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
    Job save(Job job);

    Optional<Job> findById(String tenantId, String id);

    List<Job> findAll(String tenantId);
}
