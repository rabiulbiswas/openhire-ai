package com.openhire.jobservice.controller;

import com.openhire.jobservice.configuration.HiringConfiguration.HiringStageSet;
import com.openhire.jobservice.configuration.HiringConfiguration.PipelineTemplate;
import com.openhire.jobservice.dto.request.StageSetRequest;
import com.openhire.jobservice.dto.request.TemplateRequest;
import com.openhire.jobservice.service.HiringConfigurationService;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import java.util.List;
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
    private final HiringConfigurationService service;

    public HiringConfigurationController(HiringConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/hiring-stages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    List<HiringStageSet> stages() {
        return service.stages(tenant());
    }

    @PutMapping("/hiring-stages")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")
    HiringStageSet saveStages(@Valid @RequestBody StageSetRequest request) {
        return service.saveStages(tenant(), request.key(), request.name(), request.stages());
    }

    @GetMapping("/pipeline-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    List<PipelineTemplate> templates() {
        return service.templates(tenant());
    }

    @PutMapping("/pipeline-templates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")
    PipelineTemplate saveTemplate(@Valid @RequestBody TemplateRequest request) {
        return service.saveTemplate(tenant(), request.key(), request.name(), request.stageIds());
    }

    @GetMapping("/pipeline-templates/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    PipelineTemplate resolve(@RequestParam String key) {
        return service.resolve(tenant(), key);
    }

    private String tenant() {
        return TenantContext.requiredTenantId();
    }
}
