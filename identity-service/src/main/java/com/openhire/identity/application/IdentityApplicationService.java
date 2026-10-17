package com.openhire.identity.application;

import static com.openhire.identity.domain.IdentityModels.ApiKeyStatus.REVOKED;
import static com.openhire.identity.domain.IdentityModels.InvitationStatus.ACCEPTED;
import static com.openhire.identity.domain.IdentityModels.InvitationStatus.PENDING;
import static com.openhire.identity.domain.IdentityModels.MemberStatus.DISABLED;
import static com.openhire.identity.domain.IdentityModels.OrganizationStatus.SUSPENDED;

import com.openhire.identity.application.port.IdentityEventPublisher;
import com.openhire.identity.application.port.IdentityProvider;
import com.openhire.identity.application.port.IdentityRepositories.ApiKeys;
import com.openhire.identity.application.port.IdentityRepositories.Invitations;
import com.openhire.identity.application.port.IdentityRepositories.Members;
import com.openhire.identity.application.port.IdentityRepositories.Organizations;
import com.openhire.identity.application.port.IdentityRepositories.Roles;
import com.openhire.identity.application.port.IdentityRepositories.SetupProgresses;
import com.openhire.identity.application.port.IdentityRepositories.TenantSettings;
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

    public Member updateMemberRoles(Actor actor, String organizationId, String memberId, Set<String> roleNames) {
        requireRolesExist(organizationId, roleNames);
        var current = members.findMemberById(organizationId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member not found"));
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
        return roles.findRoles(organizationId);
    }

    public Role createRole(String organizationId, String name, String description, Set<String> permissions) {
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
        requireRolesExist(organizationId, roleNames);
        var normalizedEmail = IdentityPolicy.normalizeEmail(email);
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
        return issueInvitation(organizationId, current.email(), current.roles());
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
