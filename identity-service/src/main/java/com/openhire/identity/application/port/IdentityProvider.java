package com.openhire.identity.application.port;

import java.util.Set;

public interface IdentityProvider {
    String provisionOrganization(String organizationId, String name);

    String provisionMember(String organizationId, String email, String displayName, Set<String> roles);

    void updateMemberRoles(String providerUserId, Set<String> roles);

    void disableMember(String providerUserId);
}
