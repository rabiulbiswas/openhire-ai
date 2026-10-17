package com.openhire.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
public class GatewaySecurityConfiguration {
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
            @Value("${openhire.ui.staff-url:http://localhost:5173/}") String staffUiUrl,
            @Value("${openhire.ui.partner-url:http://localhost:5174/jobs}") String partnerUiUrl) {
        org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler loginSuccessHandler =
                (filter, authentication) -> {
                    boolean agency = false;
                    if (authentication
                                    instanceof
                                    org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
                                            oauth
                            && oauth.getPrincipal().getAttribute("realm_access") instanceof java.util.Map<?, ?> realm
                            && realm.get("roles") instanceof java.util.Collection<?> roles)
                        agency = roles.contains("AGENCY_RECRUITER");
                    return new RedirectServerAuthenticationSuccessHandler(agency ? partnerUiUrl : staffUiUrl)
                            .onAuthenticationSuccess(filter, authentication);
                };
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
                                        "/api/v1/public/offers/**")))))
                .authorizeExchange(exchange -> exchange
                        // The SPA must be able to ask whether a session exists. When there is no
                        // authenticated principal, BffSessionController returns
                        // {"authenticated": false}; the browser then starts the staff login flow.
                        // Keeping this endpoint public also prevents a fetch request from silently
                        // following Spring Security's HTML login redirect.
                        .pathMatchers(HttpMethod.GET, "/bff/session")
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
                .oauth2Login(login -> login.authenticationSuccessHandler(loginSuccessHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}))
                .build();
    }
}
