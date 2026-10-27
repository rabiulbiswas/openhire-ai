package com.openhire.identity.service;

import static com.openhire.identity.domain.IdentityModels.ApiKeyStatus.REVOKED;
import static com.openhire.identity.domain.IdentityModels.InvitationStatus.ACCEPTED;
import static com.openhire.identity.domain.IdentityModels.InvitationStatus.PENDING;
import static com.openhire.identity.domain.IdentityModels.MemberStatus.DISABLED;
import static com.openhire.identity.domain.IdentityModels.OrganizationStatus.SUSPENDED;

import com.openhire.identity.domain.IdentityModels.ApiKey;
import com.openhire.identity.domain.IdentityModels.AuthPolicy;
import com.openhire.identity.domain.IdentityModels.BreakGlassRequest;
import com.openhire.identity.domain.IdentityModels.Invitation;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.domain.IdentityModels.Organization;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import com.openhire.identity.domain.IdentityModels.Role;
import com.openhire.identity.domain.IdentityModels.SetupProgress;
import com.openhire.identity.domain.IdentityModels.SsoSettings;
import com.openhire.identity.domain.IdentityModels.TenantQuota;
import com.openhire.identity.domain.IdentityModels.TenantSecuritySettings;
import com.openhire.identity.domain.IdentityPolicy;
import com.openhire.identity.service.port.IdentityEventPublisher;
import com.openhire.identity.service.port.IdentityProvider;
import com.openhire.identity.service.port.IdentityRepositories.ApiKeys;
import com.openhire.identity.service.port.IdentityRepositories.Invitations;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.identity.service.port.IdentityRepositories.Organizations;
import com.openhire.identity.service.port.IdentityRepositories.Roles;
import com.openhire.identity.service.port.IdentityRepositories.SetupProgresses;
import com.openhire.identity.service.port.IdentityRepositories.TenantSettings;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IdentityApplicationService {
    @org.springframework.beans.factory.annotation.Value("${openhire.portals.enabled:false}")
    private boolean tenantPortals;

    @org.springframework.beans.factory.annotation.Value("${openhire.portals.base-domain:localhost}")
    private String portalDomain;

    @org.springframework.beans.factory.annotation.Value("${openhire.portals.scheme:http}")
    private String portalScheme;

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Organizations organizations;
    private final Members members;
    private final Roles roles;
    private final Invitations invitations;
    private final ApiKeys apiKeys;
    private final SetupProgresses setupProgresses;
    private final TenantSettings tenantSettings;
    private final IdentityProvider identityProvider;
    private final IdentityEventPublisher events;
    private final Clock clock;
    private final AuditRecorder audit;

    public IdentityApplicationService(
            Organizations organizations,
            Members members,
            Roles roles,
            Invitations invitations,
            ApiKeys apiKeys,
            SetupProgresses setupProgresses,
            TenantSettings tenantSettings,
            IdentityProvider identityProvider,
            IdentityEventPublisher events,
            Clock clock,
            AuditRecorder audit) {
        this.organizations = organizations;
        this.members = members;
        this.roles = roles;
        this.invitations = invitations;
        this.apiKeys = apiKeys;
        this.setupProgresses = setupProgresses;
        this.tenantSettings = tenantSettings;
        this.identityProvider = identityProvider;
        this.events = events;
        this.clock = clock;
        this.audit = audit;
    }

    public Organization createOrganization(Actor actor, String name, String slug) {
        var cleanSlug = IdentityPolicy.validateSlug(slug);
        organizations.findBySlug(cleanSlug).ifPresent(existing -> {
            throw new IllegalArgumentException("Organization slug is already in use");
        });
        var organization = new Organization(
                UUID.randomUUID().toString(), name.trim(), cleanSlug, OrganizationStatus.ACTIVE, clock.instant());
        identityProvider.provisionOrganization(organization.id(), organization.name());
        organizations.save(organization);
        seedSystemRoles(organization.id());
        setupProgresses.save(new SetupProgress(organization.id(), Set.of("organization"), clock.instant()));
        tenantSettings.save(defaultSettings(organization.id()));
        events.publish("identity.org.created.v1", organization.id(), organization.id(), Map.of("slug", cleanSlug));
        auditEntry(actor, organization.id(), "ORGANIZATION_CREATED", "Organization", organization.id())
                .detail("slug", cleanSlug)
                .done();
        return organization;
    }

    public List<Organization> organizations() {
        return organizations.findAll();
    }

    public record OnboardedOrganization(Organization organization, IssuedInvitation adminInvitation) {}

    public OnboardedOrganization onboardOrganization(Actor actor, String name, String slug, String adminEmail) {
        var email = IdentityPolicy.normalizeEmail(adminEmail);
        var organization = createOrganization(actor, name, slug);
        return new OnboardedOrganization(organization, inviteOrganizationAdmin(actor, organization.id(), email));
    }

    public IssuedInvitation inviteOrganizationAdmin(Actor actor, String id, String email) {
        var issued = invite(id, email, Set.of("ORG_ADMIN"));
        auditEntry(
                        actor,
                        id,
                        "ORGANIZATION_ADMIN_INVITED",
                        "Invitation",
                        issued.invitation().id())
                .done();
        return issued;
    }

    public Map<String, Object> organizationOverview(String id) {
        var organization =
                organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        var users = members.findMembers(id);
        var pending = invitations.findInvitations(id).stream()
                .filter(i -> i.status() == PENDING && i.expiresAt().isAfter(clock.instant()))
                .count();
        return Map.of(
                "organization",
                organization,
                "totalUsers",
                users.size(),
                "activeUsers",
                users.stream()
                        .filter(u -> u.status() == com.openhire.identity.domain.IdentityModels.MemberStatus.ACTIVE)
                        .count(),
                "administrators",
                users.stream()
                        .filter(u -> u.status() == com.openhire.identity.domain.IdentityModels.MemberStatus.ACTIVE
                                && u.roles().contains("ORG_ADMIN"))
                        .count(),
                "pendingInvitations",
                pending,
                "businessPlanStatus",
                "NOT_IMPLEMENTED");
    }

    public Organization changeOrganizationStatus(Actor actor, String id, OrganizationStatus status) {
        var current =
                organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        var updated = organizations.save(
                new Organization(current.id(), current.name(), current.slug(), status, current.createdAt()));
        events.publish("identity.org.status-changed.v1", id, id, Map.of("status", status.name()));
        auditEntry(actor, id, "ORGANIZATION_STATUS_CHANGED", "Organization", id)
                .detail("from", current.status().name())
                .detail("status", status.name())
                .done();
        return updated;
    }

    public List<Member> members(String organizationId) {
        return members.findMembers(organizationId);
    }

    public Map<String, String> updateMemberDisplayName(String tenantId, String subject, String name) {
        if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank())
            throw new IllegalArgumentException("Tenant and user are required");
        if (name == null || name.isBlank() || name.trim().length() > 200)
            throw new IllegalArgumentException("Name is required, maximum 200 characters");
        var current = members.findMemberById(tenantId, subject)
                .orElseThrow(() -> new ResourceNotFoundException("Team member not found"));
        if (!tenantId.equals(current.organizationId()) || current.status() == DISABLED)
            throw new ResourceNotFoundException("Team member not found");
        if (!members.updateDisplayName(tenantId, subject, name.trim()))
            throw new ResourceNotFoundException("Team member not found");
        auditEntry(new Actor(subject, null), tenantId, "PROFILE_UPDATED", "Member", subject)
                .done();
        return Map.of("name", name.trim());
    }

    /** Minimal tenant-scoped display lookup; never exposes the administrative member directory. */
    public Map<String, String> memberDisplayNames(String tenantId, List<String> ids) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Tenant is required");
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 200))
            throw new IllegalArgumentException("Provide between 1 and 100 valid user IDs");
        var names = new java.util.LinkedHashMap<String, String>();
        ids.stream().distinct().forEach(id -> members.findMemberById(tenantId, id)
                .ifPresent(member -> {
                    if (tenantId.equals(member.organizationId())
                            && member.displayName() != null
                            && !member.displayName().isBlank()) names.put(id, member.displayName());
                }));
        return Map.copyOf(names);
    }

    public Member updateMemberRoles(Actor actor, String organizationId, String memberId, Set<String> roleNames) {
        requireRolesExist(organizationId, roleNames);
        var current = members.findMemberById(organizationId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member not found"));
        if (current.roles().contains("SUPER_ADMIN"))
            throw new IllegalArgumentException("Platform accounts cannot be managed through a tenant");
        identityProvider.updateMemberRoles(current.id(), roleNames);
        var updated = members.save(new Member(
                current.id(),
                organizationId,
                current.email(),
                current.displayName(),
                current.status(),
                roleNames,
                current.createdAt()));
        events.publish("identity.user.roles-changed.v1", organizationId, memberId, Map.of("roles", roleNames));
        auditEntry(actor, organizationId, "ROLE_CHANGED", "User", memberId)
                .detail("roleCount", roleNames.size())
                .detail("roles", String.join(",", new java.util.TreeSet<>(roleNames)))
                .done();
        return updated;
    }

    public Member disableMember(Actor actor, String organizationId, String memberId) {
        var current = members.findMemberById(organizationId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member not found"));
        if (current.roles().contains("SUPER_ADMIN"))
            throw new IllegalArgumentException("Platform accounts cannot be managed through a tenant");
        identityProvider.disableMember(current.id());
        var updated = members.save(new Member(
                current.id(),
                organizationId,
                current.email(),
                current.displayName(),
                DISABLED,
                current.roles(),
                current.createdAt()));
        events.publish("identity.user.disabled.v1", organizationId, memberId, Map.of());
        auditEntry(actor, organizationId, "USER_REMOVED", "User", memberId).done();
        return updated;
    }

    public List<Role> roles(String organizationId) {
        if (tenantPortals) seedAdditionalRoles(organizationId);
        return roles.findRoles(organizationId).stream()
                .filter(role -> !"COMPLIANCE_OFFICER".equalsIgnoreCase(role.name()))
                .toList();
    }

    public Role createRole(String organizationId, String name, String description, Set<String> permissions) {
        if ("COMPLIANCE_OFFICER".equalsIgnoreCase(name.trim()))
            throw new IllegalArgumentException("Compliance Officer is retired; use Organisation Admin");
        if ("SUPER_ADMIN".equalsIgnoreCase(name.trim())) {
            throw new IllegalArgumentException("Platform super administrator is not a tenant role");
        }
        var role = new Role(
                UUID.randomUUID().toString(), organizationId, name.trim(), description.trim(), permissions, false);
        roles.save(role);
        events.publish("identity.tenant.role-created.v1", organizationId, role.id(), Map.of("name", role.name()));
        return role;
    }

    public Role updatePermissions(String organizationId, String roleId, Set<String> permissions) {
        var current = roles.findRoleById(organizationId, roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        var updated = roles.save(new Role(
                current.id(),
                organizationId,
                current.name(),
                current.description(),
                permissions,
                current.systemRole()));
        events.publish(
                "identity.tenant.permissions-changed.v1", organizationId, roleId, Map.of("permissions", permissions));
        return updated;
    }

    public IssuedInvitation invite(String organizationId, String email, Set<String> roleNames) {
        requireActiveOrganization(organizationId);
        if (tenantPortals) seedAdditionalRoles(organizationId);
        requireRolesExist(organizationId, roleNames);
        var normalizedEmail = IdentityPolicy.normalizeEmail(email);
        if (tenantPortals) return provisionInvitedMember(organizationId, normalizedEmail, roleNames);
        if (members.findByEmail(organizationId, normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("A team member with this email already exists");
        }
        return issueInvitation(organizationId, normalizedEmail, roleNames);
    }

    public List<Invitation> invitations(String organizationId) {
        return invitations.findInvitations(organizationId);
    }

    public IssuedInvitation resendInvitation(String organizationId, String invitationId) {
        var current = invitations
                .findInvitationById(organizationId, invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        requireRolesExist(organizationId, current.roles());
        if (tenantPortals) return invite(organizationId, current.email(), current.roles());
        return issueInvitation(organizationId, current.email(), current.roles());
    }

    private String portalOrigin(Organization organization) {
        return portalScheme + "://" + organization.slug() + "." + portalDomain;
    }

    private IssuedInvitation provisionInvitedMember(String organizationId, String email, Set<String> roleNames) {
        var organization = organizations.findById(organizationId).orElseThrow();
        var existing = members.findByEmail(organizationId, email);
        if (existing.isPresent()
                && existing.get().status() != com.openhire.identity.domain.IdentityModels.MemberStatus.INVITED)
            throw new IllegalArgumentException("Member already exists; use account recovery instead");
        if (existing.isPresent() && !existing.get().roles().equals(roleNames))
            throw new IllegalArgumentException(
                    "Use the audited role update before resending an invitation with different roles");
        identityProvider.configureTenantLogin(portalOrigin(organization));
        var providerId = existing.map(Member::id)
                .orElseGet(() -> identityProvider.provisionMember(organizationId, email, email, roleNames));
        members.save(new Member(
                providerId,
                organizationId,
                email,
                email,
                com.openhire.identity.domain.IdentityModels.MemberStatus.INVITED,
                roleNames,
                existing.map(Member::createdAt).orElse(clock.instant())));
        var issued = issueInvitation(organizationId, email, roleNames);
        // Fail explicitly if mail delivery fails. The INVITED member can be retried safely.
        identityProvider.sendActivation(providerId, portalOrigin(organization));
        return new IssuedInvitation(issued.invitation(), "");
    }

    public Map<String, Object> tenantAccess(String slug, String subject) {
        var organization =
                organizations.findBySlug(slug).orElseThrow(() -> new ResourceNotFoundException("Tenant not found"));
        requireActiveOrganization(organization.id());
        if (subject == null || subject.isBlank())
            return Map.of("id", organization.id(), "name", organization.name(), "slug", organization.slug());
        var member = members.findMemberById(organization.id(), subject)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant membership not found"));
        if (member.status() == DISABLED) throw new IllegalArgumentException("Membership is disabled");
        return Map.of(
                "id",
                organization.id(),
                "name",
                organization.name(),
                "slug",
                organization.slug(),
                "roles",
                member.roles());
    }

    /** Called only by the gateway after verified OIDC login and host/role validation. */
    public void activateTenantMember(String slug, String subject) {
        var access = tenantAccess(slug, subject);
        var organizationId = access.get("id").toString();
        var member = members.findMemberById(organizationId, subject).orElseThrow();
        if (member.status() == com.openhire.identity.domain.IdentityModels.MemberStatus.INVITED) {
            members.save(new Member(
                    member.id(),
                    member.organizationId(),
                    member.email(),
                    member.displayName(),
                    com.openhire.identity.domain.IdentityModels.MemberStatus.ACTIVE,
                    member.roles(),
                    member.createdAt()));
        }
        // Retry-safe: a previous login may have activated the member before updating its invitations.
        for (var invitation : invitations.findInvitations(organizationId)) {
            if (invitation.status() == PENDING && invitation.email().equals(member.email())) {
                invitations.save(new Invitation(
                        invitation.id(),
                        invitation.organizationId(),
                        invitation.email(),
                        invitation.roles(),
                        invitation.tokenHash(),
                        ACCEPTED,
                        invitation.expiresAt(),
                        invitation.createdAt()));
            }
        }
    }

    private void seedAdditionalRoles(String id) {
        for (var name : Set.of("HR_ADMIN", "EMPLOYEE", "AGENCY_RECRUITER", "CANDIDATE")) {
            if (roles.findRoleByName(id, name).isEmpty()) {
                try {
                    roles.save(
                            new Role(UUID.randomUUID().toString(), id, name, name.replace('_', ' '), Set.of(), true));
                } catch (org.springframework.dao.DuplicateKeyException concurrentSeed) {
                    /* Another invite installed it. */
                }
            }
        }
    }

    public InvitationView invitation(String rawToken) {
        var invitation = invitations
                .findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        IdentityPolicy.requireAcceptable(invitation, clock);
        var organization = organizations
                .findById(invitation.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return new InvitationView(
                invitation.id(), organization.name(), invitation.email(), invitation.roles(), invitation.expiresAt());
    }

    public Member acceptInvitation(String rawToken, String displayName) {
        var invitation = invitations
                .findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found"));
        IdentityPolicy.requireAcceptable(invitation, clock);
        requireActiveOrganization(invitation.organizationId());
        requireRolesExist(invitation.organizationId(), invitation.roles());
        var providerId = identityProvider.provisionMember(
                invitation.organizationId(), invitation.email(), displayName, invitation.roles());
        var member = members.save(new Member(
                providerId,
                invitation.organizationId(),
                invitation.email(),
                displayName.trim(),
                com.openhire.identity.domain.IdentityModels.MemberStatus.ACTIVE,
                invitation.roles(),
                clock.instant()));
        invitations.save(new Invitation(
                invitation.id(),
                invitation.organizationId(),
                invitation.email(),
                invitation.roles(),
                invitation.tokenHash(),
                ACCEPTED,
                invitation.expiresAt(),
                invitation.createdAt()));
        events.publish(
                "identity.user.invite-accepted.v1",
                invitation.organizationId(),
                member.id(),
                Map.of("email", member.email()));
        return member;
    }

    public IssuedApiKey createApiKey(Actor actor, String organizationId, String name, Set<String> scopes) {
        var issued = issueApiKey(organizationId, name, scopes);
        auditEntry(
                        actor,
                        organizationId,
                        "API_KEY_CREATED",
                        "ApiKey",
                        issued.apiKey().id())
                .detail("scopeCount", scopes.size())
                .done();
        return issued;
    }

    private IssuedApiKey issueApiKey(String organizationId, String name, Set<String> scopes) {
        requireActiveOrganization(organizationId);
        var rawSecret = randomToken(32);
        var prefix = "oh_" + randomToken(6);
        var value = new ApiKey(
                UUID.randomUUID().toString(),
                organizationId,
                name.trim(),
                prefix,
                hash(rawSecret),
                scopes,
                com.openhire.identity.domain.IdentityModels.ApiKeyStatus.ACTIVE,
                clock.instant(),
                null);
        apiKeys.save(value);
        events.publish("identity.tenant.api-key-created.v1", organizationId, value.id(), Map.of("prefix", prefix));
        return new IssuedApiKey(value, prefix + "." + rawSecret);
    }

    public List<ApiKey> apiKeys(String organizationId) {
        return apiKeys.findApiKeys(organizationId);
    }

    public IssuedApiKey rotateApiKey(Actor actor, String organizationId, String apiKeyId) {
        var current = apiKeys.findApiKeyById(organizationId, apiKeyId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
        removeApiKey(organizationId, apiKeyId);
        var issued = issueApiKey(organizationId, current.name(), current.scopes());
        auditEntry(actor, organizationId, "API_KEY_ROTATED", "ApiKey", apiKeyId)
                .detail("newKeyId", issued.apiKey().id())
                .done();
        return issued;
    }

    public void revokeApiKey(Actor actor, String organizationId, String apiKeyId) {
        removeApiKey(organizationId, apiKeyId);
        auditEntry(actor, organizationId, "API_KEY_REVOKED", "ApiKey", apiKeyId).done();
    }

    private void removeApiKey(String organizationId, String apiKeyId) {
        var current = apiKeys.findApiKeyById(organizationId, apiKeyId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found"));
        apiKeys.save(new ApiKey(
                current.id(),
                organizationId,
                current.name(),
                current.prefix(),
                current.secretHash(),
                current.scopes(),
                REVOKED,
                current.createdAt(),
                current.lastUsedAt()));
        events.publish("identity.tenant.api-key-revoked.v1", organizationId, apiKeyId, Map.of());
    }

    public ValidatedApiKey validateApiKey(String presented) {
        var separator = presented == null ? -1 : presented.indexOf('.');
        if (separator < 1 || separator == presented.length() - 1) {
            throw new IllegalArgumentException("Invalid API key");
        }
        var prefix = presented.substring(0, separator);
        var secret = presented.substring(separator + 1);
        var key = apiKeys.findApiKeyByPrefix(prefix)
                .filter(value -> value.status() == com.openhire.identity.domain.IdentityModels.ApiKeyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (!MessageDigest.isEqual(
                key.secretHash().getBytes(StandardCharsets.UTF_8), hash(secret).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid API key");
        }
        apiKeys.save(new ApiKey(
                key.id(),
                key.organizationId(),
                key.name(),
                key.prefix(),
                key.secretHash(),
                key.scopes(),
                key.status(),
                key.createdAt(),
                clock.instant()));
        return new ValidatedApiKey(key.organizationId(), key.scopes());
    }

    public SetupProgress setupProgress(String organizationId) {
        return setupProgresses
                .find(organizationId)
                .orElseGet(() -> new SetupProgress(organizationId, Set.of(), clock.instant()));
    }

    public SetupProgress completeSetupStep(String organizationId, String step) {
        var current = setupProgress(organizationId);
        var completed = new java.util.HashSet<>(current.completedSteps());
        completed.add(step);
        return setupProgresses.save(new SetupProgress(organizationId, completed, clock.instant()));
    }

    public TenantSecuritySettings securitySettings(String organizationId) {
        return tenantSettings.findSettings(organizationId).orElseGet(() -> defaultSettings(organizationId));
    }

    public TenantSecuritySettings updateAuthPolicy(String organizationId, AuthPolicy policy) {
        if (policy.maxFailedAttempts() < 3 || policy.lockoutMinutes() < 1) {
            throw new IllegalArgumentException("Lockout policy is below the security minimum");
        }
        var current = securitySettings(organizationId);
        var updated = tenantSettings.save(
                new TenantSecuritySettings(organizationId, policy, current.sso(), current.quota(), clock.instant()));
        events.publish("identity.tenant.auth-policy-changed.v1", organizationId, organizationId, Map.of());
        return updated;
    }

    public TenantSecuritySettings updateSso(Actor actor, String organizationId, SsoSettings sso) {
        var current = securitySettings(organizationId);
        var updated = tenantSettings.save(new TenantSecuritySettings(
                organizationId, current.authPolicy(), sso, current.quota(), clock.instant()));
        events.publish(
                "identity.tenant.sso-changed.v1", organizationId, organizationId, Map.of("enabled", sso.enabled()));
        auditEntry(actor, organizationId, "SSO_CONFIG_CHANGED", "SsoConfig", organizationId)
                .detail("enabled", sso.enabled())
                .detail("protocol", sso.protocol())
                .detail("scimEnabled", sso.scimEnabled())
                .done();
        return updated;
    }

    public TenantSecuritySettings updateQuota(String organizationId, TenantQuota quota) {
        var current = securitySettings(organizationId);
        var updated = tenantSettings.save(new TenantSecuritySettings(
                organizationId, current.authPolicy(), current.sso(), quota, clock.instant()));
        events.publish("identity.tenant.quota-changed.v1", organizationId, organizationId, Map.of());
        return updated;
    }

    public BreakGlassRequest requestBreakGlass(Actor actor, String organizationId, String requestedBy, String reason) {
        var request = new BreakGlassRequest(
                UUID.randomUUID().toString(),
                organizationId,
                requestedBy,
                reason.trim(),
                "PENDING",
                clock.instant(),
                clock.instant().plus(Duration.ofMinutes(30)),
                List.of("REQUESTED:" + clock.instant()));
        tenantSettings.saveBreakGlass(request);
        events.publish(
                "identity.tenant.break-glass-requested.v1",
                organizationId,
                request.id(),
                Map.of("requestedBy", requestedBy));
        auditEntry(actor, organizationId, "BREAK_GLASS_GRANTED", "BreakGlass", request.id())
                .detail("accessMinutes", 30)
                .done();
        return request;
    }

    public List<BreakGlassRequest> breakGlassRequests(String organizationId) {
        return tenantSettings.findBreakGlassRequests(organizationId);
    }

    private AuditEntry auditEntry(Actor actor, String tenantId, String action, String resourceType, String resourceId) {
        return new AuditEntry(
                AuditRecord.of(tenantId, action).user(actor.id(), actor.name()).resource(resourceType, resourceId));
    }

    /** Collects details, then hands the record to the recorder, which never throws. */
    private final class AuditEntry {
        private final AuditRecord.Builder builder;

        private AuditEntry(AuditRecord.Builder builder) {
            this.builder = builder;
        }

        AuditEntry detail(String key, Object value) {
            builder.detail(key, value);
            return this;
        }

        void done() {
            audit.record(builder.build());
        }
    }

    private IssuedInvitation issueInvitation(String organizationId, String email, Set<String> roleNames) {
        var rawToken = randomToken(32);
        var invitation = new Invitation(
                UUID.randomUUID().toString(),
                organizationId,
                email,
                roleNames,
                hash(rawToken),
                PENDING,
                clock.instant().plus(INVITATION_TTL),
                clock.instant());
        invitations.save(invitation);
        events.publish("identity.user.invited.v1", organizationId, invitation.id(), Map.of("email", email));
        return new IssuedInvitation(invitation, rawToken);
    }

    private TenantSecuritySettings defaultSettings(String organizationId) {
        return new TenantSecuritySettings(
                organizationId,
                new AuthPolicy(false, 5, 15, Set.of()),
                new SsoSettings(false, "OIDC", "", "", false),
                new TenantQuota(100, 20, 100),
                clock.instant());
    }

    private void seedSystemRoles(String organizationId) {
        if (tenantPortals) seedAdditionalRoles(organizationId);
        roles.save(new Role(
                UUID.randomUUID().toString(),
                organizationId,
                "ORG_ADMIN",
                "Organization administrator",
                Set.of("identity:*", "jobs:*", "candidates:*", "compliance:read"),
                true));
        roles.save(new Role(
                UUID.randomUUID().toString(),
                organizationId,
                "RECRUITER",
                "Recruiter",
                Set.of("jobs:*", "candidates:*", "interviews:*", "offers:*"),
                true));
        roles.save(new Role(
                UUID.randomUUID().toString(),
                organizationId,
                "HIRING_MANAGER",
                "Hiring manager",
                Set.of("jobs:read", "jobs:approve", "candidates:read", "decisions:write"),
                true));
        roles.save(new Role(
                UUID.randomUUID().toString(),
                organizationId,
                "INTERVIEWER",
                "Interviewer",
                Set.of("interviews:read", "scorecards:write"),
                true));
    }

    private void requireRolesExist(String organizationId, Set<String> requested) {
        if (requested.isEmpty()) throw new IllegalArgumentException("At least one role is required");
        if (requested.stream().anyMatch(name -> "COMPLIANCE_OFFICER".equalsIgnoreCase(name.trim())))
            throw new IllegalArgumentException("Compliance Officer is retired; use Organisation Admin");
        if (tenantPortals
                && !Set.of(
                                "ORG_ADMIN",
                                "RECRUITER",
                                "HR_ADMIN",
                                "HIRING_MANAGER",
                                "INTERVIEWER",
                                "EMPLOYEE",
                                "AGENCY_RECRUITER",
                                "CANDIDATE")
                        .containsAll(requested)) {
            throw new IllegalArgumentException("Only supported tenant roles can be assigned through tenant onboarding");
        }
        if (requested.stream().anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r))) {
            throw new IllegalArgumentException("Platform super administrator cannot be assigned through a tenant");
        }
        var available =
                roles.findRoles(organizationId).stream().map(Role::name).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(requested)) {
            throw new IllegalArgumentException("One or more roles do not exist in this organization");
        }
    }

    private void requireActiveOrganization(String organizationId) {
        var organization = organizations
                .findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (organization.status() == SUSPENDED) {
            throw new IllegalArgumentException("Organization is suspended");
        }
    }

    private static String randomToken(int bytes) {
        var value = new byte[bytes];
        RANDOM.nextBytes(value);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Who performed an action, as seen by the controller; the name is optional. */
    public record Actor(String id, String name) {}

    public record IssuedInvitation(Invitation invitation, String token) {}

    public record IssuedApiKey(ApiKey apiKey, String secret) {}

    public record InvitationView(
            String id, String organizationName, String email, Set<String> roles, java.time.Instant expiresAt) {}

    public record ValidatedApiKey(String organizationId, Set<String> scopes) {}
}
