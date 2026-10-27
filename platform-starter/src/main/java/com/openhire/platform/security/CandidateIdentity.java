package com.openhire.platform.security;

import com.openhire.platform.tenant.TenantClaims;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Verified candidate identity only. A staff role or an email supplied in a form is never sufficient. */
public record CandidateIdentity(String tenantId, String subject, String email) {
    public static CandidateIdentity require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with your candidate account");
        var claims = jwt.getClaims();
        var forbidden = Set.of("SUPER_ADMIN", "ORG_ADMIN", "RECRUITER", "HR_ADMIN", "HIRING_MANAGER", "INTERVIEWER", "EMPLOYEE", "AGENCY_RECRUITER");
        if (!(claims.get("realm_access") instanceof Map<?, ?> realm)
                || !(realm.get("roles") instanceof Collection<?> roles)
                || !roles.contains("CANDIDATE") || roles.stream().anyMatch(forbidden::contains)
                || !Boolean.TRUE.equals(claims.get("email_verified")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A verified candidate account is required");
        var tenant = TenantClaims.resolve(claims);
        var subject = jwt.getSubject();
        var email = jwt.getClaimAsString("email");
        if (tenant == null || subject == null || subject.isBlank() || email == null || email.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Candidate identity is incomplete");
        return new CandidateIdentity(tenant, subject, email.trim().toLowerCase(Locale.ROOT));
    }
}
