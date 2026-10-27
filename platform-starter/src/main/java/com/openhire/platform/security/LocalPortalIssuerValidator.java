package com.openhire.platform.security;

import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;

/** Local portal issuers share one configured signing-key endpoint, never issuer discovery. */
public final class LocalPortalIssuerValidator implements OAuth2TokenValidator<Jwt> {
    public static String origin(String value) {
        var uri = URI.create(value);
        String host = uri.getHost();
        if (!"http".equals(uri.getScheme())
                || host == null
                || !host.endsWith(".localhost")
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 80))
            throw new IllegalArgumentException("Invalid local portal origin");
        String slug = host.substring(0, host.length() - ".localhost".length());
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || slug.length() > 63
                || Set.of("auth", "api", "www", "mail", "static", "assets", "support", "localhost")
                        .contains(slug)) throw new IllegalArgumentException("Invalid portal slug");
        return "http://" + host;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            var issuer = jwt.getIssuer();
            String origin = origin(issuer.toString());
            if (!issuer.toString().equals(origin + "/auth/realms/openhire-staff"))
                throw new IllegalArgumentException("Unexpected issuer path");
            boolean admin = URI.create(origin).getHost().equals("admin.localhost");
            if (!(admin ? "openhire-platform" : "openhire-tenant").equals(jwt.getClaimAsString("azp")))
                throw new IllegalArgumentException("Unexpected portal client");
            var realm = jwt.getClaim("realm_access");
            boolean superAdmin = realm instanceof Map<?, ?> map
                    && map.get("roles") instanceof Collection<?> roles
                    && roles.contains("SUPER_ADMIN");
            if (admin != superAdmin) throw new IllegalArgumentException("Role/issuer mismatch");
            Object canonical = jwt.getClaims().get("tenant_id"),
                    legacy = jwt.getClaims().get("organization_id");
            Object tenant = canonical == null ? legacy : canonical;
            if (!admin
                    && (!(tenant instanceof String id)
                            || id.isBlank()
                            || !id.equals(id.strip())
                            || (canonical != null && legacy != null && !canonical.equals(legacy))))
                throw new IllegalArgumentException("Invalid tenant claim");
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalid) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Untrusted portal issuer or identity", null));
        }
    }
}
