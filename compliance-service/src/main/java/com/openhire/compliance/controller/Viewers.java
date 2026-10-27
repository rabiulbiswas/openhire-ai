package com.openhire.compliance.controller;

import com.openhire.compliance.service.Actor;
import com.openhire.support.Access;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Tenant, actor and role checks for the staff endpoints. Local security mode (development only) skips roles. */
@Component
class Viewers {
    static final Set<String> READERS = Set.of("ORG_ADMIN", "SUPER_ADMIN");
    static final Set<String> MANAGERS = Set.of("ORG_ADMIN");

    private final Access access;
    private final boolean local;

    Viewers(Access access, @Value("${openhire.security.mode:oidc}") String mode) {
        this.access = access;
        this.local = "local".equals(mode);
    }

    String tenant(Authentication auth) {
        return access.tenant(auth);
    }

    Actor actor(Authentication auth) {
        String name =
                auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("name") : null;
        return new Actor(access.actor(auth), name);
    }

    /** Checks the caller holds one of the roles and returns the tenant. */
    String require(Authentication auth, Set<String> roles) {
        if (!local && roles.stream().noneMatch(role -> access.role(auth, role)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compliance access required");
        return access.tenant(auth);
    }
}
