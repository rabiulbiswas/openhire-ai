package com.openhire.approval.controller;

import com.openhire.approval.dto.ApprovalDtos.CreateApprovalRequest;
import com.openhire.approval.dto.ApprovalDtos.DelegationRequest;
import com.openhire.approval.dto.ApprovalDtos.Page;
import com.openhire.approval.dto.ApprovalDtos.ReasonRequest;
import com.openhire.approval.service.ApprovalApplicationService;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ApprovalController {
    private final ApprovalApplicationService service;
    private final String internalSecret;

    public ApprovalController(
            ApprovalApplicationService service,
            @Value("${openhire.internal-secret:local-development-secret}") String internalSecret) {
        this.service = service;
        this.internalSecret = internalSecret;
    }

    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    Object create(
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody CreateApprovalRequest request) {
        return service.create(tenant(), actor, request);
    }

    @PostMapping("/internal/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    Object createInternal(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(name = "X-Actor-Id", defaultValue = "system") String actor,
            @Valid @RequestBody CreateApprovalRequest request) {
        if (!internalSecret.equals(secret)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return service.create(tenantId, actor, request);
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    Object list(
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) String status) {
        var effectiveScope = "me".equals(assignee) ? "waiting_on_me" : scope;
        return Page.of(service.list(tenant(), actor, effectiveScope, status));
    }

    @GetMapping("/approvals/counts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    Object counts(@RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return service.counts(tenant(), actor);
    }

    @GetMapping("/approvals/sla-overdue")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN')")
    Object overdue() {
        return Page.of(service.overdue(tenant()));
    }

    @GetMapping("/approvals/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    Object get(@PathVariable String id) {
        return service.get(tenant(), id);
    }

    @PostMapping("/approvals/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    void approve(
            @PathVariable String id, @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        service.approve(tenant(), id, actor);
    }

    @PostMapping("/approvals/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    void reject(
            @PathVariable String id,
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody ReasonRequest request) {
        service.reject(tenant(), id, request.reason(), actor);
    }

    @PostMapping("/approvals/{id}/request-changes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    void requestChanges(
            @PathVariable String id,
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody ReasonRequest request) {
        service.requestChanges(tenant(), id, request.reason(), actor);
    }

    @PostMapping("/approvals/{id}/undo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    void undo(@PathVariable String id) {
        service.undo(tenant(), id);
    }

    @PostMapping("/approval-delegations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER')")
    void delegate(
            @RequestHeader(name = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody DelegationRequest request) {
        service.delegate(tenant(), request.id(), actor, request.toUser());
    }

    private String tenant() {
        return TenantContext.requiredTenantId();
    }
}
