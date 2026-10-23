package com.openhire.identity.controller;

import com.openhire.identity.domain.IdentityModels.Organization;
import com.openhire.identity.domain.IdentityModels.TenantQuota;
import com.openhire.identity.service.IdentityApplicationService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Canonical platform API. Legacy routes remain intact until the maintenance cutover. */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantPlatformController {
    private final IdentityApplicationService service;

    public TenantPlatformController(IdentityApplicationService service) {
        this.service = service;
    }

    @GetMapping
    Object tenants() {
        return service.organizations();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Object create(@Valid @RequestBody IdentityController.CreateOrganizationRequest request) {
        return service.createOrganization(IdentityController.actor(), request.name(), request.slug());
    }

    public record OnboardedTenant(Organization tenant, IdentityController.InvitationIssuedResponse adminInvitation) {}

    @PostMapping("/onboard")
    ResponseEntity<OnboardedTenant> onboard(@Valid @RequestBody IdentityController.OnboardRequest request) {
        var issued = service.onboardOrganization(
                IdentityController.actor(), request.name(), request.slug(), request.adminEmail());
        var invitation = issued.adminInvitation();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new OnboardedTenant(
                        issued.organization(),
                        new IdentityController.InvitationIssuedResponse(
                                IdentityController.InvitationResponse.from(invitation.invitation()),
                                invitation.token())));
    }

    @GetMapping("/{id}")
    Object overview(@PathVariable String id) {
        var result = new LinkedHashMap<>(service.organizationOverview(id));
        result.put("tenant", result.remove("organization"));
        return result;
    }

    @PostMapping("/{id}/admin-invitations")
    ResponseEntity<IdentityController.InvitationIssuedResponse> invite(
            @PathVariable String id, @Valid @RequestBody IdentityController.AdminInviteRequest request) {
        var issued = service.inviteOrganizationAdmin(IdentityController.actor(), id, request.email());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new IdentityController.InvitationIssuedResponse(
                        IdentityController.InvitationResponse.from(issued.invitation()), issued.token()));
    }

    @PatchMapping("/{id}/status")
    Object status(@PathVariable String id, @Valid @RequestBody IdentityController.StatusRequest request) {
        return service.changeOrganizationStatus(IdentityController.actor(), id, request.status());
    }

    @GetMapping("/{id}/quota")
    Object quota(@PathVariable String id) {
        return service.securitySettings(id).quota();
    }

    @PutMapping("/{id}/quota")
    Object quota(@PathVariable String id, @Valid @RequestBody TenantQuota request) {
        return service.updateQuota(id, request).quota();
    }

    @PostMapping("/{id}/break-glass")
    Object requestAccess(@PathVariable String id, @Valid @RequestBody IdentityController.BreakGlassRequest request) {
        return tenantAccess(
                service.requestBreakGlass(IdentityController.actor(), id, request.requestedBy(), request.reason()));
    }

    @GetMapping("/{id}/break-glass")
    Object accessRequests(@PathVariable String id) {
        return service.breakGlassRequests(id).stream().map(this::tenantAccess).toList();
    }

    private TenantAccessRequest tenantAccess(com.openhire.identity.domain.IdentityModels.BreakGlassRequest request) {
        return new TenantAccessRequest(
                request.id(),
                request.organizationId(),
                request.requestedBy(),
                request.reason(),
                request.status(),
                request.requestedAt(),
                request.expiresAt(),
                request.auditTrail());
    }

    public record TenantAccessRequest(
            String id,
            String tenantId,
            String requestedBy,
            String reason,
            String status,
            java.time.Instant requestedAt,
            java.time.Instant expiresAt,
            java.util.List<String> auditTrail) {}
}
