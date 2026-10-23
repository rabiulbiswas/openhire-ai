package com.openhire.identity.service.port;

import java.util.Set;

public interface IdentityProvider {
    String provisionOrganization(String organizationId, String name);

    String provisionMember(String organizationId, String email, String displayName, Set<String> roles);

    void updateMemberRoles(String providerUserId, Set<String> roles);

    void disableMember(String providerUserId);

    default void configureTenantLogin(String origin) {
        throw new IllegalStateException("Tenant login requires the Keycloak identity provider");
    }

    default void sendActivation(String providerUserId, String origin) {
        throw new IllegalStateException("Password activation requires the Keycloak identity provider");
    }
}
