package com.openhire.gateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "openhire.portals.enabled", havingValue = "true")
public class TenantPortalFilters {
    @Bean
    @Order(-110)
    WebFilter portalHostBoundary(TenantPortalAccess portals) {
        return (exchange, chain) -> {
            var path = exchange.getRequest().getPath().value();
            if (path.startsWith("/actuator/health")) return chain.filter(exchange);
            // No externally supplied internal/tenant/actor credentials reach downstream services.
            var clean = exchange.mutate()
                    .request(r -> r.headers(h -> {
                        h.remove("X-Tenant-Id");
                        h.remove("X-Actor-Id");
                        h.remove("X-Internal-Secret");
                        h.remove("X-Public-Tenant-Id");
                    }))
                    .build();
            // Revoked/suspended memberships must still be able to clear their browser and SSO sessions.
            if (path.equals("/bff/logout")) {
                portals.slug(clean);
                var result = chain.filter(clean);
                return portals.sameHostAuth()
                        ? result.contextWrite(context -> context.put(
                                LocalPortalOidcConfiguration.ORIGIN,
                                LocalPortalIssuerValidator.origin(
                                        portals.loginUri(clean).toString())))
                        : result;
            }
            if (path.startsWith("/api/v1/internal/")) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return portals.resolve(clean, null).flatMap(tenant -> {
                var requestedTenant = clean.getRequest().getQueryParams().get("tenantId");
                if (requestedTenant != null
                        && requestedTenant.stream()
                                .anyMatch(id -> !tenant.get("id").equals(id))) {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
                var routed = clean;
                if (path.equals("/api/v1/public/applications") || path.startsWith("/api/v1/public/candidate-registrations")) {
                    if ("admin".equals(portals.slug(clean))) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    routed = clean.mutate().request(request -> request.headers(headers ->
                            headers.set("X-Public-Tenant-Id", tenant.get("id").toString()))).build();
                }
                if ((path.equals("/api/v1/public/consent") || path.equals("/api/v1/public/jobs")
                        || path.startsWith("/api/v1/public/jobs/"))
                        && org.springframework.http.HttpMethod.GET.equals(
                                clean.getRequest().getMethod())) {
                    if ("admin".equals(portals.slug(clean))) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }
                    // Public consent has no token relay. Use only the active host's registry ID,
                    // for anonymous applicants as well as staff; never a global/default tenant.
                    var uri = org.springframework.web.util.UriComponentsBuilder.fromUri(
                                    clean.getRequest().getURI())
                            .replaceQueryParam("tenantId", tenant.get("id"))
                            .build(true)
                            .toUri();
                    routed = clean.mutate().request(request -> request.uri(uri)).build();
                }
                // Tenant activation/recovery belongs to Keycloak, not the legacy token completion API.
                if (path.startsWith("/api/v1/public/invitations/")
                        || path.startsWith("/api/v1/public/verifications/")) {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                }
                var registration = portals.registration(clean);
                if ((path.startsWith("/oauth2/authorization/") && !path.equals("/oauth2/authorization/" + registration))
                        || (path.startsWith("/login/oauth2/code/")
                                && !path.equals("/login/oauth2/code/" + registration))) {
                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    return exchange.getResponse().setComplete();
                }
                if (path.equals("/login")) {
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse()
                            .getHeaders()
                            .setLocation(java.net.URI.create("/oauth2/authorization/" + registration));
                    return exchange.getResponse().setComplete();
                }
                var result = chain.filter(routed);
                return portals.sameHostAuth()
                        ? result.contextWrite(context -> context.put(
                                LocalPortalOidcConfiguration.ORIGIN,
                                LocalPortalIssuerValidator.origin(
                                        portals.loginUri(clean).toString())))
                        : result;
            });
        };
    }

    @Bean
    @Order(-50)
    WebFilter portalMembershipBoundary(TenantPortalAccess portals) {
        return (exchange, chain) -> {
            var path = exchange.getRequest().getPath().value();
            if (path.startsWith("/actuator/health")
                    || path.equals("/bff/logout")
                    || path.startsWith("/oauth2/")
                    || path.startsWith("/login/")) return chain.filter(exchange);
            // Materialize the decision separately; Mono<Void> must not cause a second chain invocation.
            return exchange.getPrincipal()
                    .flatMap(p -> portals.authorize(exchange, p).thenReturn(true))
                    .defaultIfEmpty(false)
                    .flatMap(ignored -> chain.filter(exchange));
        };
    }
}
