package com.openhire.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openhire.identity.domain.IdentityModels.ApiKey;
import com.openhire.identity.domain.IdentityModels.ApiKeyStatus;
import com.openhire.identity.domain.IdentityModels.Member;
import com.openhire.identity.domain.IdentityModels.MemberStatus;
import com.openhire.identity.domain.IdentityModels.Organization;
import com.openhire.identity.domain.IdentityModels.OrganizationStatus;
import com.openhire.identity.domain.IdentityModels.Role;
import com.openhire.identity.domain.IdentityModels.SsoSettings;
import com.openhire.identity.service.IdentityApplicationService.Actor;
import com.openhire.identity.service.port.IdentityEventPublisher;
import com.openhire.identity.service.port.IdentityProvider;
import com.openhire.identity.service.port.IdentityRepositories.ApiKeys;
import com.openhire.identity.service.port.IdentityRepositories.Invitations;
import com.openhire.identity.service.port.IdentityRepositories.Members;
import com.openhire.identity.service.port.IdentityRepositories.Organizations;
import com.openhire.identity.service.port.IdentityRepositories.Roles;
import com.openhire.identity.service.port.IdentityRepositories.SetupProgresses;
import com.openhire.identity.service.port.IdentityRepositories.TenantSettings;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdentityAuditTest {
    @Test
    void retiredComplianceRoleIsHiddenAndCannotBeCreatedOrAssigned() {
        when(roles.findRoles("org-1")).thenReturn(List.of(role("COMPLIANCE_OFFICER"), role("ORG_ADMIN")));
        assertThat(service.roles("org-1")).extracting(Role::name).containsExactly("ORG_ADMIN");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.createRole("org-1", "COMPLIANCE_OFFICER", "legacy", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateMemberRoles(ADMIN, "org-1", "user-9", Set.of("COMPLIANCE_OFFICER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retired");
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
    }

    private static final Actor ADMIN = new Actor("admin-1", "Asha Admin");

    private final Organizations organizations = mock(Organizations.class);
    private final Members members = mock(Members.class);
    private final Roles roles = mock(Roles.class);
    private final ApiKeys apiKeys = mock(ApiKeys.class);
    private final TenantSettings tenantSettings = mock(TenantSettings.class);
    private final List<String> audited = new ArrayList<>();
    private boolean auditBroken;
    private final AuditRecorder audit = new AuditRecorder(
            new AuditRecorder.Outbox() {
                public void add(String eventId, String tenantId, String json) {
                    if (auditBroken) throw new IllegalStateException("store down");
                    audited.add(json);
                }

                public List<Pending> pending(int limit) {
                    return List.of();
                }

                public void remove(String eventId) {}
            },
            (key, json) -> {},
            "identity-service",
            true);
    private final IdentityApplicationService service = new IdentityApplicationService(
            organizations,
            members,
            roles,
            mock(Invitations.class),
            apiKeys,
            mock(SetupProgresses.class),
            tenantSettings,
            mock(IdentityProvider.class),
            mock(IdentityEventPublisher.class),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            audit);

    @Test
    void profileRenamePersistsWithoutChangingRolesOrAuditIdentity() {
        when(members.findMemberById("org-1", "user-9"))
                .thenReturn(Optional.of(new Member(
                        "user-9",
                        "org-1",
                        "private@example.test",
                        "Old Name",
                        MemberStatus.ACTIVE,
                        Set.of("RECRUITER"),
                        Instant.EPOCH)));
        when(members.updateDisplayName("org-1", "user-9", "New Name")).thenReturn(true);
        service.updateMemberDisplayName("org-1", "user-9", "  New Name  ");
        org.mockito.Mockito.verify(members).updateDisplayName("org-1", "user-9", "New Name");
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("PROFILE_UPDATED", "user-9")
                .doesNotContain("New Name", "private@example.test");
    }

    @Test
    void profileRenameRejectsOtherTenantDisabledAndInvalidNames() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateMemberDisplayName("org-1", "missing", "New Name"))
                .isInstanceOf(com.openhire.platform.web.ResourceNotFoundException.class);
        when(members.findMemberById("org-1", "disabled"))
                .thenReturn(Optional.of(new Member(
                        "disabled",
                        "org-1",
                        "private@example.test",
                        "Old",
                        MemberStatus.DISABLED,
                        Set.of(),
                        Instant.EPOCH)));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateMemberDisplayName("org-1", "disabled", "New Name"))
                .isInstanceOf(com.openhire.platform.web.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateMemberDisplayName("org-1", "user-9", " "))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateMemberDisplayName("org-1", "user-9", "x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verify(members, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void displayNamesAreTenantScopedAndExposeOnlyNames() {
        when(members.findMemberById("org-1", "user-9"))
                .thenReturn(Optional.of(new Member(
                        "user-9",
                        "org-1",
                        "private@example.test",
                        "Jane Creator",
                        MemberStatus.DISABLED,
                        Set.of("RECRUITER"),
                        Instant.EPOCH)));
        when(members.findMemberById("org-1", "foreign-user"))
                .thenReturn(Optional.of(new Member(
                        "foreign-user",
                        "org-2",
                        "foreign@example.test",
                        "Other Tenant",
                        MemberStatus.ACTIVE,
                        Set.of(),
                        Instant.EPOCH)));
        assertThat(service.memberDisplayNames("org-1", List.of("user-9", "foreign-user", "missing", "user-9")))
                .containsExactlyEntriesOf(java.util.Map.of("user-9", "Jane Creator"));
        org.mockito.Mockito.verify(members).findMemberById("org-1", "user-9");
        org.mockito.Mockito.verify(members).findMemberById("org-1", "foreign-user");
        org.mockito.Mockito.verify(members).findMemberById("org-1", "missing");
        org.mockito.Mockito.verifyNoMoreInteractions(members);
    }

    @Test
    void displayNameLookupRejectsUnboundedOrUnscopedRequests() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memberDisplayNames("", List.of("user-9")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.memberDisplayNames("org-1", java.util.Collections.nCopies(101, "user-9")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.memberDisplayNames("org-1", List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(members);
    }

    @Test
    void roleChangeIsAuditedWithActorAndNoPersonalData() {
        when(roles.findRoles("org-1")).thenReturn(List.of(role("RECRUITER"), role("INTERVIEWER")));
        when(members.findMemberById("org-1", "user-9"))
                .thenReturn(Optional.of(new Member(
                        "user-9",
                        "org-1",
                        "jane@example.com",
                        "Jane Doe",
                        MemberStatus.ACTIVE,
                        Set.of("INTERVIEWER"),
                        Instant.EPOCH)));
        when(members.save(any())).thenAnswer(call -> call.getArgument(0));

        service.updateMemberRoles(ADMIN, "org-1", "user-9", Set.of("RECRUITER"));

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"ROLE_CHANGED\"", "\"tenantId\":\"org-1\"")
                .contains("\"id\":\"admin-1\"", "\"type\":\"User\"", "\"id\":\"user-9\"")
                .contains("\"roleCount\":1")
                .doesNotContain("jane@example.com", "Jane Doe");
    }

    @Test
    void userRemovalIsAudited() {
        when(members.findMemberById("org-1", "user-9"))
                .thenReturn(Optional.of(new Member(
                        "user-9",
                        "org-1",
                        "jane@example.com",
                        "Jane Doe",
                        MemberStatus.ACTIVE,
                        Set.of(),
                        Instant.EPOCH)));
        when(members.save(any())).thenAnswer(call -> call.getArgument(0));

        service.disableMember(ADMIN, "org-1", "user-9");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"USER_REMOVED\"", "\"id\":\"user-9\"")
                .doesNotContain("jane@example.com");
    }

    @Test
    void apiKeyCreationNeverRecordsTheSecret() {
        when(organizations.findById("org-1")).thenReturn(Optional.of(organization("org-1")));
        when(apiKeys.save(any())).thenAnswer(call -> call.getArgument(0));

        var issued = service.createApiKey(ADMIN, "org-1", "CI key", Set.of("jobs:read"));

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains(
                        "\"action\":\"API_KEY_CREATED\"",
                        "\"id\":\"" + issued.apiKey().id() + "\"")
                .doesNotContain(issued.secret(), "CI key");
    }

    @Test
    void rotationProducesOneRotatedRecordOnly() {
        when(organizations.findById("org-1")).thenReturn(Optional.of(organization("org-1")));
        when(apiKeys.findApiKeyById("org-1", "key-1")).thenReturn(Optional.of(apiKey("key-1")));
        when(apiKeys.save(any())).thenAnswer(call -> call.getArgument(0));

        var issued = service.rotateApiKey(ADMIN, "org-1", "key-1");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"API_KEY_ROTATED\"", "\"id\":\"key-1\"")
                .contains("\"newKeyId\":\"" + issued.apiKey().id() + "\"");
    }

    @Test
    void revocationIsAudited() {
        when(apiKeys.findApiKeyById("org-1", "key-1")).thenReturn(Optional.of(apiKey("key-1")));

        service.revokeApiKey(ADMIN, "org-1", "key-1");

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst()).contains("\"action\":\"API_KEY_REVOKED\"", "\"id\":\"key-1\"");
    }

    @Test
    void ssoChangeRecordsFlagsButNotIssuerOrClient() {
        when(tenantSettings.findSettings("org-1")).thenReturn(Optional.empty());
        when(tenantSettings.save(any())).thenAnswer(call -> call.getArgument(0));

        service.updateSso(
                ADMIN, "org-1", new SsoSettings(true, "OIDC", "https://idp.example.com", "client-abc", false));

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"SSO_CONFIG_CHANGED\"", "\"enabled\":true", "\"protocol\":\"OIDC\"")
                .doesNotContain("idp.example.com", "client-abc");
    }

    @Test
    void organizationStatusChangeIsAuditedUnderTheOrganization() {
        when(organizations.findById("org-1")).thenReturn(Optional.of(organization("org-1")));
        when(organizations.save(any())).thenAnswer(call -> call.getArgument(0));

        service.changeOrganizationStatus(ADMIN, "org-1", OrganizationStatus.SUSPENDED);

        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .contains("\"action\":\"ORGANIZATION_STATUS_CHANGED\"", "\"tenantId\":\"org-1\"")
                .contains("\"status\":\"SUSPENDED\"", "\"from\":\"ACTIVE\"")
                .doesNotContain("Acme");
    }

    @Test
    void organizationCreationAndBreakGlassAreAudited() {
        when(organizations.findBySlug("acme")).thenReturn(Optional.empty());

        var created = service.createOrganization(ADMIN, "Acme", "acme");
        service.requestBreakGlass(ADMIN, created.id(), "admin-1", "Investigating a customer ticket");

        assertThat(audited).hasSize(2);
        assertThat(audited.get(0))
                .contains("\"action\":\"ORGANIZATION_CREATED\"", "\"tenantId\":\"" + created.id() + "\"")
                .doesNotContain("\"Acme\"");
        assertThat(audited.get(1))
                .contains("\"action\":\"BREAK_GLASS_GRANTED\"", "\"id\":\"admin-1\"")
                .doesNotContain("Investigating");
    }

    @Test
    void anAuditFailureNeverBreaksTheAdminAction() {
        auditBroken = true;
        when(apiKeys.findApiKeyById("org-1", "key-1")).thenReturn(Optional.of(apiKey("key-1")));

        service.revokeApiKey(ADMIN, "org-1", "key-1");

        assertThat(audited).isEmpty();
    }

    private static Role role(String name) {
        return new Role("role-" + name, "org-1", name, name, Set.of(), true);
    }

    private static Organization organization(String id) {
        return new Organization(id, "Acme", "acme", OrganizationStatus.ACTIVE, Instant.EPOCH);
    }

    private static ApiKey apiKey(String id) {
        return new ApiKey(
                id, "org-1", "CI key", "oh_abc", "hash", Set.of("jobs:read"), ApiKeyStatus.ACTIVE, Instant.EPOCH, null);
    }
}
