package com.openhire.identity.config;

import com.openhire.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevents a valid user token from being replayed with another tenant header. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantClaimAuthorizationFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/api/v1/public/")
                || path.startsWith("/api/v1/internal/")
                || path.startsWith("/api/v1/platform/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            var tokenTenant = jwt.getToken().getClaimAsString("organization_id");
            if (tokenTenant == null || tokenTenant.isBlank()) {
                tokenTenant = jwt.getToken().getClaimAsString("tenant_id");
            }
            var requestedTenant = TenantContext.requiredTenantId();
            if (tokenTenant == null || !requestedTenant.equals(tokenTenant)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tenant does not match authenticated identity");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
