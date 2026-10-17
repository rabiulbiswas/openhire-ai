package com.openhire.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private final String defaultTenantId;

    public TenantContextFilter(@Value("${openhire.tenant.default-id:}") String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

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
        var tenantId = request.getHeader(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = defaultTenantId;
        }
        if ((tenantId == null || tenantId.isBlank()) && request.getRequestURI().startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing X-Tenant-Id header");
            return;
        }

        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.set(tenantId.trim());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
