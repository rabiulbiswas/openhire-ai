package com.openhire.talent.adapter.in.web;

import com.openhire.support.Access;
import com.openhire.talent.application.InvitationService.Viewer;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class Viewers {
    private final Access access;

    Viewers(Access access) {
        this.access = access;
    }

    /** Tenant of a signed-in recruiter, HR admin or org admin; hiring managers and interviewers are refused. */
    String staffTenant(Authentication auth) {
        access.requireStaff(auth);
        return access.tenant(auth);
    }

    Viewer viewer(Authentication auth) {
        var roles = auth == null
                ? Set.<String>of()
                : auth.getAuthorities().stream()
                        .map(value -> value.getAuthority().replaceFirst("^ROLE_", ""))
                        .collect(Collectors.toSet());
        return new Viewer(access.actor(auth), roles);
    }
}
