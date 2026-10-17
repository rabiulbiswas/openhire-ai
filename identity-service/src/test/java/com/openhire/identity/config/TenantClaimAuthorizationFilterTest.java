package com.openhire.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.platform.tenant.TenantContextFilter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantClaimAuthorizationFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAHeaderForAnotherTenant() throws Exception {
        authenticate("tenant-a");
        var request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("X-Tenant-Id", "tenant-b");
        var response = new MockHttpServletResponse();
        var claimFilter = new TenantClaimAuthorizationFilter();

        new TenantContextFilter("")
                .doFilter(
                        request,
                        response,
                        (innerRequest, innerResponse) ->
                                claimFilter.doFilter(innerRequest, innerResponse, new MockFilterChain()));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsTheTenantFromTheVerifiedToken() throws Exception {
        authenticate("tenant-a");
        var request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("X-Tenant-Id", "tenant-a");
        var response = new MockHttpServletResponse();
        var claimFilter = new TenantClaimAuthorizationFilter();

        new TenantContextFilter("")
                .doFilter(
                        request,
                        response,
                        (innerRequest, innerResponse) ->
                                claimFilter.doFilter(innerRequest, innerResponse, new MockFilterChain()));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void authenticate(String tenant) {
        var jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of("alg", "none"),
                Map.of("sub", "user-1", "organization_id", tenant));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
