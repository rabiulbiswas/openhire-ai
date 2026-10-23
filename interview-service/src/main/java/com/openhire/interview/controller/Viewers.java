package com.openhire.interview.controller;

import com.openhire.interview.domain.InterviewTypes.Viewer;
import com.openhire.support.Access;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class Viewers {
    private final Access access;

    Viewers(Access access) {
        this.access = access;
    }

    String tenant(Authentication auth) {
        return access.tenant(auth);
    }

    Viewer viewer(Authentication auth) {
        var roles = auth == null
                ? Set.<String>of()
                : auth.getAuthorities().stream()
                        .map(value -> value.getAuthority().replaceFirst("^ROLE_", ""))
                        .collect(Collectors.toSet());
        String email = null;
        String name = null;
        if (auth instanceof JwtAuthenticationToken jwt) {
            email = jwt.getToken().getClaimAsString("email");
            name = jwt.getToken().getClaimAsString("name");
        }
        return new Viewer(access.actor(auth), email, name, roles);
    }

    /** Coordinators plan and change interviews; interviewers only read their rounds and write their scorecards. */
    Viewer coordinator(Authentication auth) {
        var viewer = viewer(auth);
        if (!viewer.coordinator())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recruiter or hiring manager access required");
        return viewer;
    }

    Viewer participant(Authentication auth) {
        var viewer = viewer(auth);
        if (!viewer.coordinator() && !viewer.roles().contains("INTERVIEWER"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Interview access required");
        return viewer;
    }
}
