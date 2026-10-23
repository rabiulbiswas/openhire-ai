package com.openhire.identity.config;

import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.domain.IdentityModels.MemberStatus;
import com.openhire.identity.domain.IdentityModels.Organization;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import com.openhire.identity.domain.IdentityModels.Role;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.identity.service.port.IdentityRepositories.Organizations;
import com.openhire.identity.service.port.IdentityRepositories.Roles;
import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Inserts a small, deterministic local data set after MongoDB is available.
 * Every lookup uses a tenant-scoped natural key, so application restarts are safe.
 */
@Component
@ConditionalOnProperty(name = "openhire.bootstrap.sample-data.enabled", havingValue = "true")
public class SampleIdentityDataSeeder implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SampleIdentityDataSeeder.class);
    private static final String ADMIN_ROLE = "ORG_ADMIN";
    private static final String RECRUITER_ROLE = "RECRUITER";
    private static final String SECOND_ORGANIZATION_ID = "demo-org-two";

    private final Organizations organizations;
    private final Roles roles;
    private final Members members;
    private final Clock clock;
    private final String organizationId;
    private final String organizationName;
    private final String organizationSlug;
    private final String adminEmail;
    private final String recruiterEmail;

    public SampleIdentityDataSeeder(
            Organizations organizations,
            Roles roles,
            Members members,
            Clock clock,
            @Value("${openhire.bootstrap.sample-data.organization-id:}") String organizationId,
            @Value("${openhire.bootstrap.sample-data.organization-name:Local workspace}") String organizationName,
            @Value("${openhire.bootstrap.sample-data.organization-slug:local-workspace}") String organizationSlug,
            @Value("${openhire.bootstrap.sample-data.admin-email:admin@openhire.local}") String adminEmail,
            @Value("${openhire.bootstrap.sample-data.recruiter-email:recruiter@openhire.local}")
                    String recruiterEmail) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("Sample seeding requires an explicit sample tenant ID");
        }
        this.organizations = organizations;
        this.roles = roles;
        this.members = members;
        this.clock = clock;
        this.organizationId = organizationId;
        this.organizationName = organizationName;
        this.organizationSlug = organizationSlug;
        this.adminEmail = adminEmail.trim().toLowerCase();
        this.recruiterEmail = recruiterEmail.trim().toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        organizations
                .findById(organizationId)
                .orElseGet(() -> organizations.save(new Organization(
                        organizationId,
                        organizationName,
                        organizationSlug,
                        OrganizationStatus.ACTIVE,
                        clock.instant())));
        organizations
                .findById(SECOND_ORGANIZATION_ID)
                .orElseGet(() -> organizations.save(new Organization(
                        SECOND_ORGANIZATION_ID,
                        "Second workspace",
                        "second-workspace",
                        OrganizationStatus.ACTIVE,
                        clock.instant())));

        seedRole(
                organizationId,
                "role-org-admin",
                ADMIN_ROLE,
                "Organization administrator",
                Set.of("identity:*", "jobs:*", "candidates:*", "compliance:read"));
        seedRole(
                organizationId,
                "role-recruiter",
                RECRUITER_ROLE,
                "Recruiter",
                Set.of("jobs:*", "candidates:*", "interviews:*", "offers:*"));

        seedMember(organizationId, "user-admin", adminEmail, "Local administrator", Set.of(ADMIN_ROLE));
        seedMember(organizationId, "user-recruiter", recruiterEmail, "Sample recruiter", Set.of(RECRUITER_ROLE));

        seedRole(
                SECOND_ORGANIZATION_ID,
                "role-org-admin-two",
                ADMIN_ROLE,
                "Organization administrator",
                Set.of("identity:*", "jobs:*", "candidates:*", "compliance:read"));
        seedMember(
                SECOND_ORGANIZATION_ID,
                "user-admin-two",
                "admin@second.openhire.local",
                "Second tenant administrator",
                Set.of(ADMIN_ROLE));

        LOG.info(
                "Sample identity data is ready for tenant {} (admin={}, recruiter={})",
                organizationId,
                adminEmail,
                recruiterEmail);
    }

    private void seedRole(String tenantId, String id, String name, String description, Set<String> permissions) {
        roles.findRoleByName(tenantId, name)
                .orElseGet(() -> roles.save(new Role(id, tenantId, name, description, permissions, true)));
    }

    private void seedMember(String tenantId, String id, String email, String displayName, Set<String> memberRoles) {
        members.findByEmail(tenantId, email)
                .orElseGet(() -> members.save(new Member(
                        id, tenantId, email, displayName, MemberStatus.ACTIVE, memberRoles, clock.instant())));
    }
}
