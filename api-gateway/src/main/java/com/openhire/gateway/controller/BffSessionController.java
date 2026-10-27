package com.openhire.gateway.controller;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/bff")
public class BffSessionController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.gateway.security.TenantPortalAccess portals;

    @org.springframework.beans.factory.annotation.Autowired
    private com.openhire.gateway.security.MemberProfileClient profiles;

    private static final Set<String> UI_ROLES = Set.of(
            "recruiter",
            "hr_admin",
            "hiring_manager",
            "interviewer",
            "org_admin",
            "employee",
            "agency_recruiter",
            "super_admin",
            "candidate");
    private final String securityMode;
    private final String defaultTenant;
    private final URI staffLogoutRedirect;
    private final URI candidateLogoutRedirect;

    public BffSessionController(
            @Value("${openhire.security.mode:local}") String securityMode,
            @Value("${openhire.tenant.default-id:}") String defaultTenant,
            @Value("${openhire.ui.auth-url:http://localhost:5176/login}") URI staffLogoutRedirect,
            @Value("${openhire.ui.candidate-auth-url:http://localhost:5176/login}") URI candidateLogoutRedirect) {
        this.securityMode = securityMode;
        this.defaultTenant = defaultTenant;
        this.staffLogoutRedirect = staffLogoutRedirect;
        this.candidateLogoutRedirect = candidateLogoutRedirect;
    }

    @GetMapping("/session")
    Mono<Map<String, Object>> session(Mono<Principal> principal, WebSession session) {
        if ("local".equalsIgnoreCase(securityMode)) {
            return Mono.just(localSession(session));
        }
        return principal
                .flatMap(value -> {
                    var current = authenticatedSession(value, session);
                    if (profiles == null) return Mono.just(current);
                    return profiles.name(value)
                            .map(name -> {
                                var user = new LinkedHashMap<String, Object>((Map<String, Object>) current.get("user"));
                                user.put("name", name);
                                current.put("user", user);
                                return current;
                            })
                            .defaultIfEmpty(current)
                            .onErrorReturn(current);
                })
                .defaultIfEmpty(Map.of("authenticated", false));
    }

    /** Nginx auth_request checks host availability without creating a browser/Redis session. */
    @GetMapping("/portal-status")
    Mono<ResponseEntity<Void>> portalStatus(org.springframework.web.server.ServerWebExchange exchange) {
        if (portals == null || !portals.enabled() || !portals.sameHostAuth())
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        // Explicit registry lookup here, independently of filter ordering: unknown/suspended hosts fail closed.
        return portals.resolve(exchange, null)
                .map(tenant -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/csrf")
    Mono<Map<String, String>> csrf(
            @org.springframework.web.bind.annotation.RequestAttribute(
                            "org.springframework.security.web.server.csrf.CsrfToken")
                    Mono<CsrfToken> token) {
        return token.map(value -> Map.of("headerName", value.getHeaderName(), "token", value.getToken()));
    }

    /** Minimal public branding; never exposes members, integration settings or credentials. */
    @GetMapping("/public-tenant")
    Mono<Map<String, Object>> publicTenant(org.springframework.web.server.ServerWebExchange exchange) {
        if (portals == null || !portals.enabled() || "admin".equals(portals.slug(exchange)))
            return Mono.error(new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
        return portals.resolve(exchange, null).map(tenant -> Map.of("id", tenant.get("id"), "name", tenant.get("name")));
    }

    @DeleteMapping("/session")
    Mono<Void> logout(WebSession session) {
        return session.invalidate();
    }

    /**
     * Browser logout clears both the Redis BFF session and the provider SSO session.
     * The redirect target is server configured, never accepted from the request, so
     * this endpoint cannot be used as an open redirect.
     */
    @GetMapping("/logout")
    Mono<ResponseEntity<Void>> browserLogout(
            Mono<Principal> principal, WebSession session, org.springframework.web.server.ServerWebExchange exchange) {
        if (portals != null && portals.enabled()) {
            var redirect = portals.loginUri(exchange);
            return principal
                    .map(value -> providerLogoutUri(value, redirect))
                    .defaultIfEmpty(redirect)
                    .flatMap(location -> session.invalidate()
                            .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                                    .location(location)
                                    .build()));
        }
        return browserLogout(principal, session);
    }

    // Retain the legacy entry point for direct callers; tenant HTTP requests use the mapped method.
    Mono<ResponseEntity<Void>> browserLogout(Mono<Principal> principal, WebSession session) {
        return principal
                .map(this::providerLogoutUri)
                .defaultIfEmpty(staffLogoutRedirect)
                .flatMap(location -> session.invalidate()
                        .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                                .location(location)
                                .build()));
    }

    private URI providerLogoutUri(Principal principal) {
        if (!(principal instanceof OAuth2AuthenticationToken authentication)
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return staffLogoutRedirect;
        }
        var redirect = "openhire-candidate".equals(authentication.getAuthorizedClientRegistrationId())
                ? candidateLogoutRedirect
                : staffLogoutRedirect;
        var issuer = user.getIssuer();
        if (issuer == null || user.getIdToken() == null) return redirect;
        return UriComponentsBuilder.fromUriString(issuer.toString())
                .path("/protocol/openid-connect/logout")
                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", redirect)
                .build()
                .encode()
                .toUri();
    }

    private URI providerLogoutUri(Principal principal, URI redirect) {
        if (!(principal instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser user)
                || user.getIssuer() == null) return redirect;
        return UriComponentsBuilder.fromUriString(user.getIssuer().toString())
                .path("/protocol/openid-connect/logout")
                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", redirect)
                .build()
                .encode()
                .toUri();
    }

    private Map<String, Object> localSession(WebSession session) {
        // Local fixture sessions require an explicit tenant; never invent a shared demo workspace.
        if (defaultTenant == null || defaultTenant.isBlank()) return Map.of("authenticated", false);
        var response = new LinkedHashMap<String, Object>();
        response.put("authenticated", true);
        response.put("sessionId", session.getId());
        response.put(
                "user", Map.of("id", "local-admin", "name", "Local administrator", "email", "admin@openhire.local"));
        response.put("tenant", Map.of("id", defaultTenant, "name", "Local workspace"));
        response.put("roles", List.of("org_admin", "recruiter"));
        response.put("activeRole", "org_admin");
        response.put(
                "permissions",
                List.of(
                        "admin:users",
                        "admin:roles",
                        "admin:integrations",
                        "admin:ai_settings",
                        "admin:sso",
                        "apikey:manage",
                        "webhook:manage",
                        "ops:dlq:replay",
                        "ai:prompt:promote",
                        "job:create",
                        "job:edit",
                        "job:publish",
                        "approval:decide",
                        "approval:delegate",
                        "application:read",
                        "candidate:read",
                        "candidate:edit"));
        response.put("expiresAt", Instant.now().plus(30, ChronoUnit.MINUTES).toString());
        return response;
    }

    private Map<String, Object> authenticatedSession(Principal principal, WebSession session) {
        var response = new LinkedHashMap<String, Object>();
        response.put("authenticated", true);
        response.put("sessionId", session.getId());
        var attributes = principal instanceof OAuth2AuthenticationToken oauth
                ? oauth.getPrincipal().getAttributes()
                : Map.<String, Object>of();
        var subject = stringClaim(attributes, "sub", principal.getName());
        var email = stringClaim(attributes, "email", principal.getName());
        var displayName = session.getAttributeOrDefault(
                "openhire.profile.name",
                stringClaim(attributes, "name", stringClaim(attributes, "preferred_username", email)));
        var tenant = com.openhire.gateway.security.TenantClaims.resolve(attributes);
        response.put("user", Map.of("id", subject, "name", displayName, "email", email));
        var roles = new LinkedHashSet<String>();
        if (principal instanceof OAuth2AuthenticationToken oauth) {
            oauth.getAuthorities().stream()
                    .map(Object::toString)
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5).toLowerCase())
                    .filter(UI_ROLES::contains)
                    .forEach(roles::add);
        }
        if (attributes.get("realm_access") instanceof Map<?, ?> realm
                && realm.get("roles") instanceof Collection<?> realmRoles) {
            realmRoles.stream()
                    .map(Object::toString)
                    .map(this::normalizeRole)
                    .filter(UI_ROLES::contains)
                    .forEach(roles::add);
        }
        if (principal instanceof OAuth2AuthenticationToken oauth
                && "openhire-candidate".equals(oauth.getAuthorizedClientRegistrationId())) {
            // Candidate realm identities must never inherit staff UI privileges.
            roles.clear();
            roles.add("candidate");
        }
        if (roles.contains("super_admin")) tenant = "platform";
        if (tenant == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Tenant claim is required");
        response.put(
                "tenant",
                Map.of(
                        "id",
                        tenant,
                        "name",
                        stringClaim(
                                attributes, "tenant_name", stringClaim(attributes, "organization_name", "OpenHire"))));
        response.put("roles", roles);
        response.put(
                "activeRole",
                List.of(
                                "super_admin",
                                "org_admin",
                                "recruiter",
                                "hr_admin",
                                "hiring_manager",
                                "interviewer",
                                "employee",
                                "agency_recruiter",
                                "candidate")
                        .stream()
                        .filter(roles::contains)
                        .findFirst()
                        .orElse(""));
        var permissions = new LinkedHashSet<String>();
        if (attributes.get("permissions") instanceof Collection<?> values) {
            if (!roles.contains("candidate"))
                values.stream().map(Object::toString).forEach(permissions::add);
        }
        roles.forEach(role -> permissions.addAll(rolePermissions(role)));
        response.put("permissions", permissions);
        response.put("expiresAt", Instant.now().plus(30, ChronoUnit.MINUTES).toString());
        return response;
    }

    private String stringClaim(Map<String, Object> attributes, String name, String fallback) {
        var value = attributes.get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String normalizeRole(String role) {
        var normalized = role.toLowerCase();
        return normalized;
    }

    // Mirrors what the services enforce today; offers and talent permissions arrive with their services.
    private static final Set<String> RECRUITER = Set.of(
            "job:create",
            "job:edit",
            "job:publish",
            "approval:read",
            "application:read",
            "candidate:read",
            "candidate:edit",
            "application:import",
            "assessment:read",
            "interview:schedule",
            "offer:create",
            "offer:send",
            "talent:search",
            "talent:invite");

    private Set<String> rolePermissions(String role) {
        return switch (role) {
            case "super_admin" -> Set.of("platform:tenants:manage", "platform:break_glass:request");
            case "org_admin" ->
                Set.of(
                        "admin:users",
                        "admin:roles",
                        "admin:integrations",
                        "admin:ai_settings",
                        "admin:sso",
                        "apikey:manage",
                        "webhook:manage",
                        "ops:dlq:replay",
                        "ai:prompt:promote",
                        "offer:approve",
                        "approval:read",
                        "approval:decide",
                        "approval:delegate",
                        "compliance:audit:read",
                        "compliance:fairness:read",
                        "compliance:consent:manage",
                        "compliance:retention:manage",
                        "privacy:execute",
                        "assessment:read",
                        "candidate:export");
            case "recruiter" -> RECRUITER;
            case "hr_admin" -> {
                var permissions = new LinkedHashSet<>(RECRUITER);
                permissions.add("application:decide");
                permissions.add("offer:approve");
                permissions.add("offer:rescind");
                permissions.add("approval:decide");
                permissions.add("approval:delegate");
                yield Set.copyOf(permissions);
            }
            case "hiring_manager" ->
                Set.of(
                        "job:approve",
                        "approval:read",
                        "approval:delegate",
                        "application:read",
                        "application:decide",
                        "assessment:read",
                        "approval:decide",
                        "candidate:read",
                        "interview:scorecard:submit",
                        "interview:scorecard:read_all",
                        "offer:approve");
            case "interviewer" -> Set.of("interview:scorecard:submit");
            case "employee" -> Set.of("internal:apply", "referral:create");
            case "agency_recruiter" -> Set.of("partner:submit");
            default -> Set.of();
        };
    }
}
