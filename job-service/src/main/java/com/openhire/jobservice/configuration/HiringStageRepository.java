package com.openhire.jobservice.configuration;

import com.openhire.jobservice.configuration.HiringConfiguration.HiringStageSet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

interface HiringStageRepository extends MongoRepository<HiringStageSet, String> {
    List<HiringStageSet> findByTenantIdOrderByName(String tenantId);

    Optional<HiringStageSet> findByTenantIdAndKey(String tenantId, String key);
}
