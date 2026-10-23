package com.openhire.offer.controller;

import com.openhire.offer.domain.OfferTypes.Viewer;
import com.openhire.support.Access;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

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
}
