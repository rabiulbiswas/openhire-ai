package com.openhire.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Documentation never inherits the permissive local-development authentication mode. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DocumentationAccess implements WebFilter {
    public static final String LOGIN_DESTINATION = "openhire.documentation.login";
    public static final String UI = "/swagger-ui/index.html";
    public static final String[] PATHS = {
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/v3/api-docs/**",
        "/v3/api-docs.yaml",
        "/openapi/**",
        "/webjars/swagger-ui/**"
    };
    private final boolean enabled;
    private final String mode;

    public DocumentationAccess(
            @Value("${springdoc.api-docs.enabled:false}") boolean enabled,
            @Value("${openhire.security.mode:local}") String mode) {
        this.enabled = enabled;
        this.mode = mode;
    }

    static boolean documentationPath(String path) {
        return path.equals("/swagger-ui.html")
                || path.equals("/swagger-ui")
                || path.startsWith("/swagger-ui/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/")
                || path.equals("/v3/api-docs.yaml")
                || path.equals("/openapi")
                || path.startsWith("/openapi/")
                || path.startsWith("/webjars/swagger-ui/");
    }

    public static boolean staffSession(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication instanceof OAuth2AuthenticationToken oauth
                && "openhire-staff".equals(oauth.getAuthorizedClientRegistrationId());
    }

    public static Mono<Void> startStaffLogin(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals(UI) && !path.equals("/swagger-ui.html") && !path.equals("/swagger-ui/")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return exchange.getSession().flatMap(session -> {
            session.getAttributes().put(LOGIN_DESTINATION, true);
            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
            exchange.getResponse()
                    .getHeaders()
                    .setLocation(java.net.URI.create("/oauth2/authorization/openhire-staff"));
            return exchange.getResponse().setComplete();
        });
    }

    public static Mono<Void> afterLogin(
            org.springframework.security.web.server.WebFilterExchange filter,
            Authentication authentication,
            String applicationUrl) {
        var exchange = filter.getExchange();
        return exchange.getSession().flatMap(session -> {
            boolean documentation = Boolean.TRUE.equals(session.getAttributes().remove(LOGIN_DESTINATION));
            if (documentation && staffSession(authentication)) {
                // Consume any saved request, but never let it override the fixed documentation destination.
                return new org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache()
                        .getRedirectUri(exchange)
                        .then(new org.springframework.security.web.server.DefaultServerRedirectStrategy()
                                .sendRedirect(exchange, java.net.URI.create(UI)));
            }
            // Do not let a previously saved API request override the role's portal destination.
            return new org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache()
                    .getRedirectUri(exchange)
                    .then(new org.springframework.security.web.server.DefaultServerRedirectStrategy()
                            .sendRedirect(exchange, java.net.URI.create(applicationUrl)));
        });
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!documentationPath(exchange.getRequest().getPath().value())) return chain.filter(exchange);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (!enabled || !"oidc".equals(mode)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}
