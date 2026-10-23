package com.openhire.gateway.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;

/** Fixed, server-owned targets: the browser cannot supply a proxy destination. */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiPortalConfiguration {
    public record ServiceSpec(String name, String environment, int port, String path) {}

    static final List<ServiceSpec> SERVICES = List.of(
            new ServiceSpec("identity-service", "IDENTITY_SERVICE_URL", 8081, "/v3/api-docs"),
            new ServiceSpec("job-service", "JOB_SERVICE_URL", 8082, "/api-docs"),
            new ServiceSpec("approval-service", "APPROVAL_SERVICE_URL", 8083, "/v3/api-docs"),
            new ServiceSpec("application-service", "APPLICATION_SERVICE_URL", 8084, "/v3/api-docs"),
            new ServiceSpec("candidate-service", "CANDIDATE_SERVICE_URL", 8085, "/v3/api-docs"),
            new ServiceSpec("resume-processor", "RESUME_PROCESSOR_URL", 8086, "/v3/api-docs"),
            new ServiceSpec("interview-service", "INTERVIEW_SERVICE_URL", 8087, "/v3/api-docs"),
            new ServiceSpec("communication-service", "COMMUNICATION_SERVICE_URL", 8088, "/v3/api-docs"),
            new ServiceSpec("notification-dispatcher", "NOTIFICATION_DISPATCHER_URL", 8089, "/v3/api-docs"),
            new ServiceSpec("offer-service", "OFFER_SERVICE_URL", 8090, "/v3/api-docs"),
            new ServiceSpec("employee-service", "EMPLOYEE_SERVICE_URL", 8091, "/v3/api-docs"),
            new ServiceSpec("ai-gateway", "AI_GATEWAY_URL", 8092, "/v3/api-docs"),
            new ServiceSpec("ai-workers", "AI_WORKERS_URL", 8093, "/v3/api-docs"),
            new ServiceSpec("talent-service", "TALENT_SERVICE_URL", 8094, "/v3/api-docs"),
            new ServiceSpec("compliance-service", "COMPLIANCE_SERVICE_URL", 8095, "/v3/api-docs"),
            new ServiceSpec("analytics-service", "ANALYTICS_SERVICE_URL", 8096, "/v3/api-docs"),
            new ServiceSpec("integration-hub", "INTEGRATION_HUB_URL", 8107, "/v3/api-docs"));

    @Bean
    RouteLocator documentationRoutes(RouteLocatorBuilder builder, Environment environment) {
        var routes = builder.routes();
        for (var service : SERVICES) {
            routes.route("openapi-" + service.name(), route -> route.order(-100)
                    .path("/openapi/services/" + service.name())
                    .and()
                    .method(HttpMethod.GET)
                    .filters(filters -> filters.setPath(service.path()).tokenRelay())
                    .uri(environment.getProperty(service.environment(), "http://localhost:" + service.port())));
        }
        return routes.build();
    }

    public Map<String, Object> swaggerConfig() {
        var urls = new java.util.ArrayList<Map<String, String>>();
        urls.add(Map.of("name", "api-gateway", "url", "/v3/api-docs"));
        SERVICES.forEach(
                service -> urls.add(Map.of("name", service.name(), "url", "/openapi/services/" + service.name())));
        return Map.ofEntries(
                Map.entry("urls", urls),
                Map.entry("urls.primaryName", "api-gateway"),
                Map.entry("supportedSubmitMethods", List.of()),
                Map.entry("tryItOutEnabled", false),
                Map.entry("persistAuthorization", false),
                Map.entry("queryConfigEnabled", false),
                Map.entry("validatorUrl", ""),
                Map.entry("filter", true),
                Map.entry("displayRequestDuration", false));
    }
}
