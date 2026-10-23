package com.openhire.gateway.security;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Same contract as platform-starter, kept servlet-free for the reactive gateway. */
public final class TenantClaims {
    private TenantClaims() {}

    public static String resolve(Map<String, Object> claims) {
        String canonical = value(claims, "tenant_id");
        String legacy = value(claims, "organization_id");
        if (canonical != null && legacy != null && !canonical.equals(legacy)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Conflicting tenant claims");
        }
        return canonical != null ? canonical : legacy;
    }

    private static String value(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid tenant claim");
        }
        return text;
    }
}
