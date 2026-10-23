package com.openhire.analytics.controller;

import com.openhire.analytics.service.DeadLetterService.Operator;
import com.openhire.support.Access;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class Viewers {
    private static final Set<String> RECRUITING_STAFF =
            Set.of("RECRUITER", "HR_ADMIN", "HIRING_MANAGER", "ORG_ADMIN", "COMPLIANCE_OFFICER", "SUPER_ADMIN");

    private final Access access;
    private final boolean local;

    Viewers(Access access, @Value("${openhire.security.mode:oidc}") String mode) {
        this.access = access;
        this.local = "local".equals(mode);
    }

    /** The tenant for analytics reads: any recruiting staff role, always the caller's own organisation. */
    String analyticsTenant(Authentication auth) {
        if (!local && RECRUITING_STAFF.stream().noneMatch(role -> access.role(auth, role)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recruiting staff access required");
        return access.tenant(auth);
    }

    void requireSuperAdmin(Authentication auth) {
        if (!local && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super admin access required");
    }

    /** Null for a super admin, who sees every tenant's event timings; the organisation for an org admin. */
    String sloTenant(Authentication auth) {
        if (local) return access.tenant(auth);
        if (access.role(auth, "SUPER_ADMIN")) return null;
        if (!access.role(auth, "ORG_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organisation admin access required");
        return access.tenant(auth);
    }

    /** Dead-letter operators: org admins for their own tenant, super admins for all. */
    Operator operator(Authentication auth) {
        boolean superAdmin = access.role(auth, "SUPER_ADMIN");
        if (!local && !superAdmin && !access.role(auth, "ORG_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organisation admin access required");
        String tenant;
        try {
            tenant = access.tenant(auth);
        } catch (ResponseStatusException noOrganisation) {
            if (!superAdmin) throw noOrganisation;
            tenant = "";
        }
        return new Operator(access.actor(auth), displayName(auth), tenant, superAdmin);
    }

    private String displayName(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            var name = jwt.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) return name;
            var email = jwt.getToken().getClaimAsString("email");
            if (email != null && !email.isBlank()) return email;
        }
        return access.actor(auth);
    }
}
