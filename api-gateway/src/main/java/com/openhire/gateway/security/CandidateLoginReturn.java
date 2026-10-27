package com.openhire.gateway.security;

import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.server.WebSession;

public final class CandidateLoginReturn {
    public static final String KEY = "candidate.returnTo";
    private CandidateLoginReturn() {}
    public static String safe(String value) {
        if (value == null) return "/candidate/portal";
        if (value.equals("/") || value.equals("/candidate/portal") || value.equals("/careers/profile")
                || value.matches("/careers/jobs/[a-zA-Z0-9_-]{1,100}(/apply)?")) return value;
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid candidate return page");
    }
    public static String consume(WebSession session, Principal principal, String fallback) {
        Object path = session.getAttributes().remove(KEY);
        if (path instanceof String value && principal instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal().getAttribute("realm_access") instanceof Map<?, ?> realm
                && realm.get("roles") instanceof Collection<?> roles && roles.contains("CANDIDATE")) return safe(value);
        return fallback;
    }
}
