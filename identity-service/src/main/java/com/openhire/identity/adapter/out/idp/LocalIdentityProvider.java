package com.openhire.identity.adapter.out.idp;

import com.openhire.identity.application.port.IdentityProvider;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "openhire.identity.provider", havingValue = "local", matchIfMissing = true)
public class LocalIdentityProvider implements IdentityProvider {
    @Override
    public String provisionOrganization(String organizationId, String name) {
        return organizationId;
    }

    @Override
    public String provisionMember(String organizationId, String email, String displayName, Set<String> roles) {
        return UUID.randomUUID().toString();
    }

    @Override
    public void updateMemberRoles(String providerUserId, Set<String> roles) {}

    @Override
    public void disableMember(String providerUserId) {}
}
