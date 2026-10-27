package com.openhire.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
public class GatewaySecurityConfiguration {
    @org.springframework.beans.factory.annotation.Autowired
    private TenantPortalAccess portals;

    @Bean
    @org.springframework.core.annotation.Order(2)
    @ConditionalOnProperty(name = "openhire.security.mode", havingValue = "oidc")
    SecurityWebFilterChain documentationSecurity(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(DocumentationAccess.PATHS))
                .securityContextRepository(
                        new org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository())
                .authorizeExchange(exchange -> exchange.anyExchange().access((authentication, context) -> authentication
                        .map(DocumentationAccess::staffSession)
                        .defaultIfEmpty(false)
                        .map(org.springframework.security.authorization.AuthorizationDecision::new)))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        (exchange, failure) -> DocumentationAccess.startStaffLogin(exchange)))
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(0)
    @ConditionalOnProperty(name = "openhire.security.mode", havingValue = "oidc")
    SecurityWebFilterChain mcpBearerSecurity(
            ServerHttpSecurity http,
            @Value("${openhire.integration.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/mcp"))
                .securityContextRepository(
                        org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
                                .getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .oauth2ResourceServer(
                        resource -> resource.jwt(jwt -> {}).authenticationEntryPoint((exchange, failure) -> {
                            exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                            exchange.getResponse()
                                    .getHeaders()
                                    .set(
                                            "WWW-Authenticate",
                                            "Bearer resource_metadata=\"" + publicBaseUrl.replaceAll("/+$", "")
                                                    + "/.well-known/oauth-protected-resource/api/v1/mcp\"");
                            return exchange.getResponse().setComplete();
                        }))
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    @ConditionalOnProperty(name = "openhire.security.mode", havingValue = "oidc")
    SecurityWebFilterChain scimBearerSecurity(ServerHttpSecurity http) {
        return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/scim/**"))
                .securityContextRepository(
                        org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
                                .getInstance())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {}))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "openhire.security.mode", havingValue = "local", matchIfMissing = true)
    SecurityWebFilterChain localSecurity(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "openhire.security.mode", havingValue = "oidc")
    SecurityWebFilterChain oidcSecurity(
            ServerHttpSecurity http,
            org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository registrations,
            @Value("${openhire.ui.staff-url:http://localhost:5173/today}") String staffUiUrl,
            @Value("${openhire.ui.partner-url:http://localhost:5174/jobs}") String partnerUiUrl,
            @Value("${openhire.ui.platform-url:http://localhost:5175/organizations}") String platformUiUrl,
            @Value("${openhire.ui.candidate-url:http://localhost:3000/portal}") String candidateUiUrl) {
        org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler loginSuccessHandler =
                (filter, authentication) -> {
                    if (portals != null && portals.enabled()) {
                        return portals.completeLogin(filter.getExchange(), authentication)
                                .then(filter.getExchange().getSession().flatMap(session -> MonoRedirect.redirect(filter.getExchange(),
                                        CandidateLoginReturn.consume(session, authentication, portals.landing(authentication)))))
                                .onErrorResume(
                                        org.springframework.web.server.ResponseStatusException.class,
                                        error -> filter.getExchange()
                                                .getSession()
                                                .flatMap(session -> session.invalidate())
                                                .then(MonoRedirect.denied(filter.getExchange())));
                    }
                    String applicationUrl = staffUiUrl;
                    if (authentication
                                    instanceof
                                    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
                                            oauth
                            && oauth.getPrincipal().getAttribute("realm_access") instanceof java.util.Map<?, ?> realm
                            && realm.get("roles") instanceof java.util.Collection<?> roles) {
                        if (roles.contains("SUPER_ADMIN")) applicationUrl = platformUiUrl;
                        else if (roles.contains("AGENCY_RECRUITER")) applicationUrl = partnerUiUrl;
                    }
                    if (authentication
                                    instanceof
                                    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
                                            oauth
                            && "openhire-candidate".equals(oauth.getAuthorizedClientRegistrationId()))
                        applicationUrl = candidateUiUrl;
                    return DocumentationAccess.afterLogin(filter, authentication, applicationUrl);
                };
        var authorizationRequests =
                new org.springframework.security.oauth2.client.web.server
                        .DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        authorizationRequests.setAuthorizationRequestCustomizer(
                org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers.withPkce());
        return http.csrf(csrf -> csrf.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // Public intake does not use ambient session authority. Status/withdrawal
                        // require an explicit high-entropy receipt header, and interview booking a
                        // signed scheduling-token header, instead.
                        .requireCsrfProtectionMatcher(new AndServerWebExchangeMatcher(
                                CsrfWebFilter.DEFAULT_CSRF_MATCHER,
                                new NegatedServerWebExchangeMatcher(ServerWebExchangeMatchers.pathMatchers(
                                        "/api/v1/public/applications/**",
                                        "/api/v1/public/api-applications",
                                        "/api/v1/public/scheduling/**",
                                        "/api/v1/public/offers/**",
                                        "/api/v1/public/candidate-registrations/**")))))
                .authorizeExchange(exchange -> exchange
                        // The SPA must be able to ask whether a session exists. When there is no
                        // authenticated principal, BffSessionController returns
                        // {"authenticated": false}; the browser then starts the staff login flow.
                        // Keeping this endpoint public also prevents a fetch request from silently
                        // following Spring Security's HTML login redirect.
                        .pathMatchers(HttpMethod.GET, "/bff/session", "/bff/public-tenant", "/bff/candidate-login")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/bff/portal-status")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/bff/logout")
                        .permitAll()
                        .pathMatchers(
                                "/actuator/health/**",
                                "/api/v1/public/**",
                                "/oauth2/**",
                                "/login/**",
                                "/.well-known/oauth-protected-resource/api/v1/mcp")
                        .permitAll()
                        .anyExchange()
                        .authenticated())
                .oauth2Login(login -> login.authorizationRequestResolver(authorizationRequests)
                        .authenticationSuccessHandler(loginSuccessHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}))
                .build();
    }

    private static final class MonoRedirect {
        static reactor.core.publisher.Mono<Void> redirect(
                org.springframework.web.server.ServerWebExchange exchange, String path) {
            return reactor.core.publisher.Mono.defer(() -> {
                exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.FOUND);
                exchange.getResponse().getHeaders().setLocation(java.net.URI.create(path));
                return exchange.getResponse().setComplete();
            });
        }

        static reactor.core.publisher.Mono<Void> denied(org.springframework.web.server.ServerWebExchange exchange) {
            return reactor.core.publisher.Mono.defer(() -> {
                exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
                return exchange.getResponse()
                        .writeWith(reactor.core.publisher.Mono.just(exchange.getResponse()
                                .bufferFactory()
                                .wrap(
                                        "This account cannot access this organisation. Sign out of the identity provider and use the correct account."
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            });
        }
    }
}
