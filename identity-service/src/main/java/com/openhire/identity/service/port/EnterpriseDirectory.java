package com.openhire.identity.service.port;

import com.openhire.identity.domain.SamlConnection;

/** External identity operations. Implementations must never adopt another tenant's existing user. */
public interface EnterpriseDirectory {
    String provision(String tenant, String stableId, String email, String displayName);

    void update(String tenant, String providerId, String displayName, boolean active);

    void configureSaml(String alias, SamlConnection connection);

    void link(String tenant, String providerId, String alias, String subject);
}
