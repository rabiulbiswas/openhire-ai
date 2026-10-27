package com.openhire.identity.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.openhire.identity.service.IdentityApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MemberProfileEndpointTest {
    @Test
    void approvalDirectoryIncludesOnlyActiveMembersOfTheRequestedTenant() {
        var service = mock(IdentityApplicationService.class);
        var now = java.time.Instant.now();
        var active = com.openhire.identity.domain.IdentityModels.MemberStatus.ACTIVE;
        when(service.members("tenant-a"))
                .thenReturn(java.util.List.of(
                        new com.openhire.identity.domain.IdentityModels.Member(
                                "manager",
                                "tenant-a",
                                "hm@example.test",
                                "Manager",
                                active,
                                java.util.Set.of("HIRING_MANAGER"),
                                now),
                        new com.openhire.identity.domain.IdentityModels.Member(
                                "requester",
                                "tenant-a",
                                "r@example.test",
                                "Recruiter",
                                active,
                                java.util.Set.of("RECRUITER"),
                                now),
                        new com.openhire.identity.domain.IdentityModels.Member(
                                "foreign",
                                "tenant-b",
                                "other@example.test",
                                "Foreign",
                                active,
                                java.util.Set.of("ORG_ADMIN"),
                                now),
                        new com.openhire.identity.domain.IdentityModels.Member(
                                "disabled",
                                "tenant-a",
                                "disabled@example.test",
                                "Disabled",
                                com.openhire.identity.domain.IdentityModels.MemberStatus.DISABLED,
                                java.util.Set.of("HIRING_MANAGER"),
                                now)));
        var controller = new IdentityController(service, "test-secret");
        var internal = (java.util.List<com.openhire.platform.security.TenantStaffDirectory.Member>)
                controller.internalApprovalMembers("test-secret", "tenant-a");
        org.assertj.core.api.Assertions.assertThat(internal)
                .extracting(com.openhire.platform.security.TenantStaffDirectory.Member::id)
                .containsExactly("manager", "requester");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                com.openhire.platform.tenant.TenantContext.class, "set", "tenant-a");
        try {
            var publicMembers = (java.util.List<com.openhire.platform.security.TenantStaffDirectory.Member>)
                    controller.approvalMembers();
            org.assertj.core.api.Assertions.assertThat(publicMembers)
                    .extracting(com.openhire.platform.security.TenantStaffDirectory.Member::id)
                    .containsExactly("manager");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    com.openhire.platform.tenant.TenantContext.class, "clear");
        }
        assertThatThrownBy(() -> controller.internalApprovalMembers("invalid", "tenant-a"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void readsAndWritesRejectAnInvalidInternalCredential() {
        var service = mock(IdentityApplicationService.class);
        var controller = new IdentityController(service, "private-test-secret");
        assertThatThrownBy(() -> controller.memberProfile("wrong", "tenant-a", "user-a"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.updateMemberProfile(
                        "wrong", new IdentityController.MemberProfileUpdate("tenant-a", "user-a", "Name")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
