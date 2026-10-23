package com.openhire.jobservice.repository.mongo;

import com.openhire.jobservice.configuration.HiringConfiguration.PipelineTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PipelineTemplateRepository extends MongoRepository<PipelineTemplate, String> {
    List<PipelineTemplate> findByTenantIdOrderByName(String tenantId);

    Optional<PipelineTemplate> findByTenantIdAndKey(String tenantId, String key);
}
