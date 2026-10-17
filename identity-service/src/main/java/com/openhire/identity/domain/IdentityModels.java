package com.openhire.identity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class IdentityModels {
    private IdentityModels() {}

    public enum OrganizationStatus {
        ACTIVE,
        SUSPENDED
    }

    public enum MemberStatus {
        INVITED,
        ACTIVE,
        DISABLED
    }

    public enum InvitationStatus {
        PENDING,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }

    public enum ApiKeyStatus {
        ACTIVE,
        REVOKED
    }

    public record Organization(String id, String name, String slug, OrganizationStatus status, Instant createdAt) {}

    public record Member(
            String id,
            String organizationId,
            String email,
            String displayName,
            MemberStatus status,
            Set<String> roles,
            Instant createdAt) {
        public Member {
            roles = Set.copyOf(roles);
        }
    }

    public record Role(
            String id,
            String organizationId,
            String name,
            String description,
            Set<String> permissions,
            boolean systemRole) {
        public Role {
            permissions = Set.copyOf(permissions);
        }
    }

    public record Invitation(
            String id,
            String organizationId,
            String email,
            Set<String> roles,
            String tokenHash,
            InvitationStatus status,
            Instant expiresAt,
            Instant createdAt) {
        public Invitation {
            roles = Set.copyOf(roles);
        }
    }

    public record ApiKey(
            String id,
            String organizationId,
            String name,
            String prefix,
            String secretHash,
            Set<String> scopes,
            ApiKeyStatus status,
            Instant createdAt,
            Instant lastUsedAt) {
        public ApiKey {
            scopes = Set.copyOf(scopes);
        }
    }

    public record SetupProgress(String organizationId, Set<String> completedSteps, Instant updatedAt) {
        public SetupProgress {
            completedSteps = Set.copyOf(completedSteps);
        }
    }

    public record TenantSecuritySettings(
            String organizationId, AuthPolicy authPolicy, SsoSettings sso, TenantQuota quota, Instant updatedAt) {}

    public record AuthPolicy(
            boolean mfaRequired, int maxFailedAttempts, int lockoutMinutes, Set<String> allowedEmailDomains) {
        public AuthPolicy {
            allowedEmailDomains = Set.copyOf(allowedEmailDomains);
        }
    }

    public record SsoSettings(boolean enabled, String protocol, String issuer, String clientId, boolean scimEnabled) {}

    public record TenantQuota(int maxUsers, int maxApiKeys, int maxActiveJobs) {}

    public record BreakGlassRequest(
            String id,
            String organizationId,
            String requestedBy,
            String reason,
            String status,
            Instant requestedAt,
            Instant expiresAt,
            List<String> auditTrail) {
        public BreakGlassRequest {
            auditTrail = List.copyOf(auditTrail);
        }
    }
}
