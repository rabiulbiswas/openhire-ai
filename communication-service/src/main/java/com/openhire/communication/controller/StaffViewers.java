package com.openhire.communication.controller;

import com.openhire.communication.service.CommunicationService.StaffViewer;
import com.openhire.support.Access;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
class StaffViewers {
    private final Access access;

    StaffViewers(Access access) {
        this.access = access;
    }

    String tenant(Authentication auth) {
        return access.tenant(auth);
    }

    /** Any signed-in staff member: notifications and preferences are personal. */
    StaffViewer viewer(Authentication auth) {
        var roles = auth == null
                ? java.util.Set.<String>of()
                : auth.getAuthorities().stream()
                        .map(value -> value.getAuthority().replaceFirst("^ROLE_", ""))
                        .collect(Collectors.toSet());
        String email = null;
        String name = null;
        if (auth instanceof JwtAuthenticationToken jwt) {
            email = jwt.getToken().getClaimAsString("email");
            name = jwt.getToken().getClaimAsString("name");
        }
        return new StaffViewer(access.actor(auth), email, name, roles);
    }

    /** Recruiting staff who may message candidates and edit templates. */
    StaffViewer recruiter(Authentication auth) {
        access.requireStaff(auth);
        return viewer(auth);
    }
}
