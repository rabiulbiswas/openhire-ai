package com.openhire.jobservice.configuration;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

public final class HiringConfiguration {
    private HiringConfiguration() {}

    @Document("hiring_stage_sets")
    @CompoundIndex(name = "tenant_key_unique", def = "{'tenantId':1,'key':1}", unique = true)
    public record HiringStageSet(
            @Id String id, String tenantId, String key, String name, List<Stage> stages, Instant updatedAt) {}

    public record Stage(String id, String name, String type, int position, boolean terminal) {}

    @Document("pipeline_templates")
    @CompoundIndex(name = "tenant_key_unique", def = "{'tenantId':1,'key':1}", unique = true)
    public record PipelineTemplate(
            @Id String id, String tenantId, String key, String name, List<String> stageIds, Instant updatedAt) {}
}
