package com.openhire.identity.controller;

import com.openhire.identity.domain.IdentityModels.ApiKey;
import com.openhire.identity.domain.IdentityModels.AuthPolicy;
import com.openhire.identity.domain.IdentityModels.Invitation;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import com.openhire.identity.domain.IdentityModels.SsoSettings;
import com.openhire.identity.domain.IdentityModels.TenantQuota;
import com.openhire.identity.service.IdentityApplicationService;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityApplicationService service;
    private final String internalSecret;

    public IdentityController(
            IdentityApplicationService service, @Value("${openhire.internal.secret}") String internalSecret) {
        this.service = service;
        this.internalSecret = internalSecret;
    }

    @PostMapping("/platform/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
        return service.createOrganization(actor(), request.name(), request.slug());
    }

    @GetMapping("/platform/organizations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object organizations() {
        return service.organizations();
    }

    @PostMapping("/platform/organizations/onboard")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    org.springframework.http.ResponseEntity<?> onboard(@Valid @RequestBody OnboardRequest request) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.onboardOrganization(actor(), request.name(), request.slug(), request.adminEmail()));
    }

    @GetMapping("/platform/organizations/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object organizationOverview(@PathVariable String id) {
        return service.organizationOverview(id);
    }

    @PostMapping("/platform/organizations/{id}/admin-invitations")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    org.springframework.http.ResponseEntity<?> adminInvitation(
            @PathVariable String id, @Valid @RequestBody AdminInviteRequest request) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.inviteOrganizationAdmin(actor(), id, request.email()));
    }

    public record OnboardRequest(@NotBlank String name, @NotBlank String slug, @NotBlank @Email String adminEmail) {}

    public record AdminInviteRequest(@NotBlank @Email String email) {}

    @PatchMapping("/platform/organizations/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object organizationStatus(@PathVariable String id, @Valid @RequestBody StatusRequest request) {
        return service.changeOrganizationStatus(actor(), id, request.status());
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ORG_ADMIN','RECRUITER')")
    Object users() {
        return service.members(tenant());
    }

    @PatchMapping("/users/{id}/roles")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object updateRoles(@PathVariable String id, @Valid @RequestBody RolesRequest request) {
        return service.updateMemberRoles(actor(), tenant(), id, request.roles());
    }

    @DeleteMapping("/users/{id}")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object disableUser(@PathVariable String id) {
        return service.disableMember(actor(), tenant(), id);
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object roles() {
        return service.roles(tenant());
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object createRole(@Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(tenant(), request.name(), request.description(), request.permissions());
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object permissions(@PathVariable String id, @Valid @RequestBody PermissionsRequest request) {
        return service.updatePermissions(tenant(), id, request.permissions());
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object invite(@Valid @RequestBody InviteRequest request) {
        var issued = service.invite(tenant(), request.email(), request.roles());
        return new InvitationIssuedResponse(InvitationResponse.from(issued.invitation()), issued.token());
    }

    @GetMapping("/invites")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object invites() {
        return service.invitations(tenant()).stream()
                .map(InvitationResponse::from)
                .toList();
    }

    @PostMapping("/invites/{id}/resend")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object resend(@PathVariable String id) {
        var issued = service.resendInvitation(tenant(), id);
        return new InvitationIssuedResponse(InvitationResponse.from(issued.invitation()), issued.token());
    }

    @GetMapping("/public/invitations/{token}")
    Object invitation(@PathVariable String token) {
        return service.invitation(token);
    }

    @PostMapping("/public/invitations/{token}/accept")
    Object accept(@PathVariable String token, @Valid @RequestBody AcceptInviteRequest request) {
        var member = service.acceptInvitation(token, request.displayName());
        return new AuthenticationCompletionResponse(true, "/recruiter/today", member);
    }

    @PostMapping("/public/verifications/{code}/complete")
    Object completeVerification(@PathVariable String code) {
        // Keycloak verifies the code; the BFF consumes this response and redirects immediately.
        return new AuthenticationCompletionResponse(true, "/recruiter/today", null);
    }

    @PostMapping("/internal/api-keys/validate")
    Object validateApiKey(
            @RequestHeader("X-Internal-Secret") String suppliedSecret,
            @Valid @RequestBody ApiKeyValidationRequest request) {
        if (!java.security.MessageDigest.isEqual(
                internalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                suppliedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid internal credential");
        }
        return service.validateApiKey(request.apiKey());
    }

    @GetMapping("/internal/tenant-access/{slug}")
    Object tenantAccess(
            @PathVariable String slug,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String subject,
            @RequestHeader("X-Internal-Secret") String suppliedSecret) {
        if (!java.security.MessageDigest.isEqual(
                internalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                suppliedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return service.tenantAccess(slug, subject);
    }

    @PostMapping("/internal/tenant-access/{slug}/activate")
    void activateTenantMember(
            @PathVariable String slug,
            @org.springframework.web.bind.annotation.RequestParam String subject,
            @RequestHeader("X-Internal-Secret") String suppliedSecret) {
        if (!java.security.MessageDigest.isEqual(
                internalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                suppliedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        service.activateTenantMember(slug, subject);
    }

    @GetMapping("/api-keys")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object apiKeys() {
        return service.apiKeys(tenant()).stream().map(ApiKeyResponse::from).toList();
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object createApiKey(@Valid @RequestBody ApiKeyRequest request) {
        var issued = service.createApiKey(actor(), tenant(), request.name(), request.scopes());
        return new ApiKeyIssuedResponse(ApiKeyResponse.from(issued.apiKey()), issued.secret());
    }

    @PostMapping("/api-keys/{id}/rotate")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object rotateApiKey(@PathVariable String id) {
        var issued = service.rotateApiKey(actor(), tenant(), id);
        return new ApiKeyIssuedResponse(ApiKeyResponse.from(issued.apiKey()), issued.secret());
    }

    @DeleteMapping("/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ORG_ADMIN')")
    void revokeApiKey(@PathVariable String id) {
        service.revokeApiKey(actor(), tenant(), id);
    }

    @GetMapping("/setup")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object setup() {
        return service.setupProgress(tenant());
    }

    @PatchMapping("/setup/{step}/complete")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object setupStep(@PathVariable String step) {
        return service.completeSetupStep(tenant(), step);
    }

    @GetMapping("/auth-policy")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object authPolicy() {
        return service.securitySettings(tenant()).authPolicy();
    }

    @PutMapping("/auth-policy")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object authPolicy(@Valid @RequestBody AuthPolicy policy) {
        return service.updateAuthPolicy(tenant(), policy).authPolicy();
    }

    @GetMapping("/sso-config")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object sso() {
        return service.securitySettings(tenant()).sso();
    }

    @PutMapping("/sso-config")
    @PreAuthorize("hasRole('ORG_ADMIN')")
    Object sso(@Valid @RequestBody SsoSettings settings) {
        return service.updateSso(actor(), tenant(), settings).sso();
    }

    @GetMapping("/platform/organizations/{id}/quota")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object quota(@PathVariable String id) {
        return service.securitySettings(id).quota();
    }

    @PutMapping("/platform/organizations/{id}/quota")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object quota(@PathVariable String id, @Valid @RequestBody TenantQuota quota) {
        return service.updateQuota(id, quota).quota();
    }

    @PostMapping("/platform/organizations/{id}/break-glass")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object breakGlass(@PathVariable String id, @Valid @RequestBody BreakGlassRequest request) {
        return service.requestBreakGlass(actor(), id, request.requestedBy(), request.reason());
    }

    @GetMapping("/platform/organizations/{id}/break-glass")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Object breakGlass(@PathVariable String id) {
        return service.breakGlassRequests(id);
    }

    private String tenant() {
        return TenantContext.requiredTenantId();
    }

    static IdentityApplicationService.Actor actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) return new IdentityApplicationService.Actor(null, null);
        String name = null;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            name = jwt.getToken().getClaimAsString("name");
            if (name == null) name = jwt.getToken().getClaimAsString("preferred_username");
        }
        return new IdentityApplicationService.Actor(authentication.getName(), name);
    }

    public record CreateOrganizationRequest(@NotBlank String name, @NotBlank String slug) {}

    public record StatusRequest(OrganizationStatus status) {}

    public record RolesRequest(@NotEmpty Set<String> roles) {}

    public record CreateRoleRequest(
            @NotBlank String name, @NotBlank String description, @NotEmpty Set<String> permissions) {}

    public record PermissionsRequest(@NotEmpty Set<String> permissions) {}

    public record InviteRequest(@Email @NotBlank String email, @NotEmpty Set<String> roles) {}

    public record AcceptInviteRequest(@NotBlank String displayName) {}

    public record ApiKeyRequest(@NotBlank String name, @NotEmpty Set<String> scopes) {}

    public record ApiKeyValidationRequest(@NotBlank String apiKey) {}

    public record BreakGlassRequest(@NotBlank String requestedBy, @NotBlank String reason) {}

    public record AuthenticationCompletionResponse(boolean verified, String redirectTo, Member member) {}

    public record InvitationIssuedResponse(InvitationResponse invitation, String token) {}

    public record InvitationResponse(String id, String email, Set<String> roles, String status, Instant expiresAt) {
        static InvitationResponse from(Invitation value) {
            return new InvitationResponse(
                    value.id(), value.email(), value.roles(), value.status().name(), value.expiresAt());
        }
    }

    public record ApiKeyIssuedResponse(ApiKeyResponse apiKey, String secret) {}

    public record ApiKeyResponse(
            String id,
            String name,
            String prefix,
            Set<String> scopes,
            String status,
            Instant createdAt,
            Instant lastUsedAt) {
        static ApiKeyResponse from(ApiKey value) {
            return new ApiKeyResponse(
                    value.id(),
                    value.name(),
                    value.prefix(),
                    value.scopes(),
                    value.status().name(),
                    value.createdAt(),
                    value.lastUsedAt());
        }
    }
}
