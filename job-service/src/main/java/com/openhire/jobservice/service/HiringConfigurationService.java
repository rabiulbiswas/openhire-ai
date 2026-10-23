package com.openhire.jobservice.service;

import com.openhire.jobservice.configuration.HiringConfiguration.HiringStageSet;
import com.openhire.jobservice.configuration.HiringConfiguration.PipelineTemplate;
import com.openhire.jobservice.configuration.HiringConfiguration.Stage;
import com.openhire.jobservice.repository.mongo.HiringStageRepository;
import com.openhire.jobservice.repository.mongo.PipelineTemplateRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HiringConfigurationService {
    private final HiringStageRepository stages;
    private final PipelineTemplateRepository templates;

    public HiringConfigurationService(HiringStageRepository stages, PipelineTemplateRepository templates) {
        this.stages = stages;
        this.templates = templates;
    }

    public List<HiringStageSet> stages(String tenant) {
        return stages.findByTenantIdOrderByName(tenant);
    }

    public HiringStageSet saveStages(String tenant, String key, String name, List<Stage> values) {
        var existing = stages.findByTenantIdAndKey(tenant, key).orElse(null);
        return stages.save(new HiringStageSet(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                tenant,
                key,
                name,
                values,
                Instant.now()));
    }

    public List<PipelineTemplate> templates(String tenant) {
        return templates.findByTenantIdOrderByName(tenant);
    }

    public PipelineTemplate saveTemplate(String tenant, String key, String name, List<String> stageIds) {
        var existing = templates.findByTenantIdAndKey(tenant, key).orElse(null);
        return templates.save(new PipelineTemplate(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                tenant,
                key,
                name,
                stageIds,
                Instant.now()));
    }

    public PipelineTemplate resolve(String tenant, String key) {
        return templates.findByTenantIdAndKey(tenant, key).orElseThrow();
    }
}
