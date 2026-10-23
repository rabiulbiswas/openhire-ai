package com.openhire.employee.controller;

import com.openhire.employee.service.EmployeeApplicationService.Viewer;
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
    private static final Set<String> OPERATORS = Set.of("ORG_ADMIN", "HR_ADMIN", "SUPER_ADMIN");

    private final Access access;
    private final boolean local;

    Viewers(Access access, @Value("${openhire.security.mode:oidc}") String mode) {
        this.access = access;
        this.local = "local".equals(mode);
    }

    String tenant(Authentication auth) {
        return access.tenant(auth);
    }

    Viewer viewer(Authentication auth) {
        return new Viewer(access.actor(auth), email(auth), access.staff(auth));
    }

    Viewer staff(Authentication auth) {
        access.requireStaff(auth);
        return viewer(auth);
    }

    /** Saga operations are limited to organisation and HR administrators. */
    void requireOperator(Authentication auth) {
        if (local) return;
        if (OPERATORS.stream().noneMatch(role -> access.role(auth, role)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "HR administrator access required");
    }

    String email(Authentication auth) {
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("email") : null;
    }
}
