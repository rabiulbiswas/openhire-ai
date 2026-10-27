package com.openhire.jobservice.controller;

import com.openhire.jobservice.service.JobCategories;
import com.openhire.platform.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/job-categories")
public class JobCategoryController {
    private final JobCategories categories;

    public JobCategoryController(JobCategories categories) {
        this.categories = categories;
    }

    public record Input(String name, boolean active, long revision) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")
    public JobCategories.Page active(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return categories.list(TenantContext.requiredTenantId(), true, page, size);
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public JobCategories.Page all(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return categories.list(TenantContext.requiredTenantId(), false, page, size);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public JobCategories.Category create(@RequestHeader("X-Actor-Id") String actor, @RequestBody Input input) {
        return categories.save(
                TenantContext.requiredTenantId(), actor, null, input.name(), input.active(), input.revision());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public JobCategories.Category update(
            @RequestHeader("X-Actor-Id") String actor, @PathVariable String id, @RequestBody Input input) {
        return categories.save(
                TenantContext.requiredTenantId(), actor, id, input.name(), input.active(), input.revision());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    public void delete(
            @RequestHeader("X-Actor-Id") String actor, @PathVariable String id, @RequestParam long revision) {
        categories.delete(TenantContext.requiredTenantId(), actor, id, revision);
    }
}
