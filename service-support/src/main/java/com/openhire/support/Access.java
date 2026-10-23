package com.openhire.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class Access {
    private final String mode;
    private final String tenant;
    private final String secret;

    public Access(
            @Value("${openhire.security.mode:oidc}") String mode,
            @Value("${openhire.tenant.default-id:}") String tenant,
            @Value("${openhire.internal-secret}") String secret) {
        this.mode = mode;
        this.tenant = tenant;
        this.secret = secret;
        if (secret.isBlank()) throw new IllegalArgumentException("Internal service secret is required");
    }

    public String tenant(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            var result = com.openhire.platform.tenant.TenantClaims.resolve(
                    jwt.getToken().getClaims());
            if (result != null && !result.isBlank()) return result;
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant claim is required");
        }
        if ("local".equals(mode) && tenant != null && !tenant.isBlank()) return tenant;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant claim is required");
    }

    public String actor(Authentication auth) {
        if ("local".equals(mode) && auth == null) return "local-recruiter";
        if (auth == null || auth.getName() == null || auth.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Subject claim is required");
        return auth.getName();
    }

    public boolean staff(Authentication auth) {
        return "local".equals(mode)
                || (auth != null
                        && auth.getAuthorities().stream().anyMatch(a -> java.util.Set.of(
                                        "ROLE_RECRUITER", "ROLE_ORG_ADMIN", "ROLE_HR_ADMIN", "ROLE_SUPER_ADMIN")
                                .contains(a.getAuthority())));
    }

    public void requireStaff(Authentication auth) {
        if (!staff(auth)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recruiter access required");
    }

    public boolean role(Authentication auth, String role) {
        return auth != null
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    public void internal(String supplied) {
        if (supplied == null
                || !MessageDigest.isEqual(
                        secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid service credentials");
    }

    public String secret() {
        return secret;
    }
}
