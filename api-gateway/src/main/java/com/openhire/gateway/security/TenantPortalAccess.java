package com.openhire.gateway.security;

import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** All tenant decisions come from a configured host and a verified identity, never browser headers. */
@Component
public class TenantPortalAccess {
    private final boolean enabled;
    private final String domain;
    private final WebClient identity;

    public TenantPortalAccess(
            @Value("${openhire.portals.enabled:false}") boolean enabled,
            @Value("${openhire.portals.base-domain:localhost}") String domain,
            @Value("${openhire.security.mode:oidc}") String securityMode,
            @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityUrl,
            @Value("${INTERNAL_SERVICE_SECRET:local-development-secret}") String secret) {
        if (enabled && !"oidc".equals(securityMode))
            throw new IllegalStateException("Tenant portals require OIDC security");
        this.enabled = enabled;
        this.domain = domain;
        this.identity = WebClient.builder()
                .baseUrl(identityUrl)
                .defaultHeader("X-Internal-Secret", secret)
                .build();
    }

    public boolean enabled() {
        return enabled;
    }

    public String slug(ServerWebExchange exchange) {
        var host = exchange.getRequest().getURI().getHost();
        if (host == null || !host.endsWith("." + domain)) throw denied("Unknown portal host");
        var slug = host.substring(0, host.length() - domain.length() - 1);
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 63) throw denied("Invalid portal host");
        if (Set.of("auth", "api", "www", "mail", "static", "assets", "support", "localhost")
                .contains(slug)) throw denied("Reserved portal host");
        return slug;
    }

    public String registration(ServerWebExchange exchange) {
        return "admin".equals(slug(exchange)) ? "openhire-platform" : "openhire-tenant";
    }

    @SuppressWarnings("unchecked")
    public Mono<Map<String, Object>> resolve(ServerWebExchange exchange, String subject) {
        var slug = slug(exchange);
        if ("admin".equals(slug)) return Mono.just(Map.of("id", "platform", "name", "OpenHire Platform", "slug", slug));
        return identity.get()
                .uri(builder -> {
                    builder.path("/api/v1/internal/tenant-access/{slug}");
                    if (subject != null) builder.queryParam("subject", subject);
                    return builder.build(slug);
                })
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(5))
                .map(value -> (Map<String, Object>) value)
                .onErrorMap(error ->
                        new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant unavailable or membership denied"));
    }

    public Mono<Void> authorize(ServerWebExchange exchange, Principal principal) {
        Map<String, Object> claims;
        String client;
        if (principal instanceof OAuth2AuthenticationToken oauth) {
            claims = oauth.getPrincipal().getAttributes();
            client = oauth.getAuthorizedClientRegistrationId();
        } else if (principal instanceof JwtAuthenticationToken jwt) {
            claims = jwt.getToken().getClaims();
            client = jwt.getToken().getClaimAsString("azp");
        } else return Mono.error(denied("Unsupported identity"));
        var authenticatedTenant = TenantClaims.resolve(claims);
        var roles = claims.get("realm_access") instanceof Map<?, ?> realm
                        && realm.get("roles") instanceof Collection<?> values
                ? values.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet())
                : Set.<String>of();
        if (!registration(exchange).equals(client))
            return Mono.error(denied("Authentication belongs to another portal"));
        if ("admin".equals(slug(exchange))) {
            if (!roles.contains("SUPER_ADMIN")) return Mono.error(denied("Platform administrator required"));
            var path = exchange.getRequest().getPath().value();
            if (path.startsWith("/api/") && !path.startsWith("/api/v1/platform/"))
                return Mono.error(denied("Tenant data is not available from the platform portal"));
            return Mono.empty();
        }
        if (roles.contains("SUPER_ADMIN")) return Mono.error(denied("Use the platform portal"));
        if (!Boolean.TRUE.equals(claims.get("email_verified")))
            return Mono.error(denied("Verify your email before signing in"));
        if (exchange.getRequest().getPath().value().startsWith("/api/v1/platform/"))
            return Mono.error(denied("Platform API is not a tenant API"));
        var subject = String.valueOf(claims.getOrDefault("sub", ""));
        if (subject.isBlank()) return Mono.error(denied("Identity has no subject"));
        return resolve(exchange, subject).flatMap(tenant -> {
            if (!tenant.get("id").equals(authenticatedTenant))
                return Mono.error(denied("Tenant does not match authenticated identity"));
            var current = tenant.get("roles") instanceof Collection<?> values
                    ? values.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet())
                    : Set.<String>of();
            var businessRoles = Set.of(
                    "ORG_ADMIN",
                    "RECRUITER",
                    "HR_ADMIN",
                    "HIRING_MANAGER",
                    "INTERVIEWER",
                    "COMPLIANCE_OFFICER",
                    "EMPLOYEE",
                    "AGENCY_RECRUITER",
                    "CANDIDATE");
            var tokenRoles =
                    roles.stream().filter(businessRoles::contains).collect(java.util.stream.Collectors.toSet());
            if (current.isEmpty() || !current.equals(tokenRoles))
                return Mono.error(denied("Roles changed; sign in again"));
            return Mono.empty();
        });
    }

    public String landing(Principal principal) {
        if (principal instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal().getAttribute("realm_access") instanceof Map<?, ?> realm
                && realm.get("roles") instanceof Collection<?> roles) {
            if (roles.contains("SUPER_ADMIN")) return "/tenants";
            if (roles.contains("ORG_ADMIN")) return "/admin/team";
            if (roles.contains("RECRUITER") || roles.contains("HR_ADMIN")) return "/";
            if (roles.contains("HIRING_MANAGER")
                    || roles.contains("INTERVIEWER")
                    || roles.contains("COMPLIANCE_OFFICER")
                    || roles.contains("EMPLOYEE")) return "/";
            if (roles.contains("AGENCY_RECRUITER")) return "/partner/jobs";
            if (roles.contains("CANDIDATE")) return "/candidate/portal";
        }
        return "/";
    }

    public Mono<Void> completeLogin(ServerWebExchange exchange, Principal principal) {
        return authorize(exchange, principal).then(Mono.defer(() -> {
            if ("admin".equals(slug(exchange))) return Mono.empty();
            return identity.post()
                    .uri(builder -> builder.path("/api/v1/internal/tenant-access/{slug}/activate")
                            .queryParam("subject", principal.getName())
                            .build(slug(exchange)))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(5))
                    .then()
                    .onErrorMap(error -> new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Account activation could not be completed; retry sign-in"));
        }));
    }

    public URI loginUri(ServerWebExchange exchange) {
        slug(exchange); // only a validated configured host can become a redirect
        var request = exchange.getRequest().getURI();
        return URI.create(request.getScheme() + "://" + request.getRawAuthority() + "/login");
    }

    private ResponseStatusException denied(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
