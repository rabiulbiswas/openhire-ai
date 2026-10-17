package com.openhire.jobservice.configuration;

import com.openhire.jobservice.configuration.HiringConfiguration.HiringStageSet;
import com.openhire.jobservice.configuration.HiringConfiguration.PipelineTemplate;
import com.openhire.jobservice.configuration.HiringConfiguration.Stage;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HiringConfigurationController {
    private final HiringStageRepository stages;
    private final PipelineTemplateRepository templates;

    public HiringConfigurationController(HiringStageRepository stages, PipelineTemplateRepository templates) {
        this.stages = stages;
        this.templates = templates;
    }

    @GetMapping("/hiring-stages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    List<HiringStageSet> stages() {
        return stages.findByTenantIdOrderByName(tenant());
    }

    @PutMapping("/hiring-stages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")
    HiringStageSet saveStages(@Valid @RequestBody StageSetRequest request) {
        var existing = stages.findByTenantIdAndKey(tenant(), request.key()).orElse(null);
        return stages.save(new HiringStageSet(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                tenant(),
                request.key(),
                request.name(),
                request.stages(),
                Instant.now()));
    }

    @GetMapping("/pipeline-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    List<PipelineTemplate> templates() {
        return templates.findByTenantIdOrderByName(tenant());
    }

    @PutMapping("/pipeline-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")
    PipelineTemplate saveTemplate(@Valid @RequestBody TemplateRequest request) {
        var existing = templates.findByTenantIdAndKey(tenant(), request.key()).orElse(null);
        return templates.save(new PipelineTemplate(
                existing == null ? UUID.randomUUID().toString() : existing.id(),
                tenant(),
                request.key(),
                request.name(),
                request.stageIds(),
                Instant.now()));
    }

    @GetMapping("/pipeline-templates/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    PipelineTemplate resolve(@RequestParam String key) {
        return templates.findByTenantIdAndKey(tenant(), key).orElseThrow();
    }

    public record StageSetRequest(@NotBlank String key, @NotBlank String name, List<@Valid Stage> stages) {}

    public record TemplateRequest(@NotBlank String key, @NotBlank String name, List<@NotBlank String> stageIds) {}

    private String tenant() {
        return TenantContext.requiredTenantId();
    }
}
