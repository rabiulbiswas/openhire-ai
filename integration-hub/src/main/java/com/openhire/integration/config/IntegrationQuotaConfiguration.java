package com.openhire.integration.config;

import com.openhire.integration.repository.mongo.IntegrationBudget;
import com.openhire.support.Access;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class IntegrationQuotaConfiguration implements WebMvcConfigurer {
    private final IntegrationBudget budget;
    private final Access access;

    IntegrationQuotaConfiguration(IntegrationBudget budget, Access access) {
        this.budget = budget;
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        budget.request(
                                access.tenant(SecurityContextHolder.getContext().getAuthentication()));
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/connectors/**", "/api/v1/webhooks/**", "/api/v1/mcp");
    }
}
