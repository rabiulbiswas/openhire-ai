package com.openhire.gateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TenantPropagationFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .map(principal -> {
                    String tenant = null;
                    String actor = principal.getName();
                    if (principal instanceof JwtAuthenticationToken jwt) {
                        tenant = TenantClaims.resolve(jwt.getToken().getClaims());
                        actor = jwt.getToken().getSubject();
                    } else if (principal instanceof OAuth2AuthenticationToken oauth) {
                        tenant = TenantClaims.resolve(oauth.getPrincipal().getAttributes());
                        actor = claim(oauth.getPrincipal().getAttributes(), "sub");
                    }
                    if (tenant != null && !tenant.isBlank()) {
                        var resolvedTenant = tenant;
                        var resolvedActor = actor;
                        return exchange.mutate()
                                .request(request -> request.headers(headers -> {
                                    headers.set("X-Tenant-Id", resolvedTenant);
                                    if (resolvedActor != null) headers.set("X-Actor-Id", resolvedActor);
                                }))
                                .build();
                    }
                    return exchange;
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return -50;
    }

    private String claim(java.util.Map<String, Object> attributes, String name) {
        var value = attributes.get(name);
        return value == null ? null : value.toString();
    }
}
