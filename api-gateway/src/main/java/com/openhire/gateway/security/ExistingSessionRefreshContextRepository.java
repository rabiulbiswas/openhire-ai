package com.openhire.gateway.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Used only after a verified OIDC refresh, never for initial login or a change of identity. */
final class ExistingSessionRefreshContextRepository implements ServerSecurityContextRepository {
    private static final String KEY =
            WebSessionServerSecurityContextRepository.DEFAULT_SPRING_SECURITY_CONTEXT_ATTR_NAME;
    private final WebSessionServerSecurityContextRepository reader = new WebSessionServerSecurityContextRepository();

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        return reader.load(exchange);
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext refreshed) {
        return exchange.getSession().flatMap(session -> {
            SecurityContext current = session.getAttribute(KEY);
            if (session.isExpired()
                    || current == null
                    || refreshed == null
                    || !(current.getAuthentication() instanceof OAuth2AuthenticationToken before)
                    || !(refreshed.getAuthentication() instanceof OAuth2AuthenticationToken after)
                    || !before.isAuthenticated()
                    || !after.isAuthenticated()
                    || !before.getName().equals(after.getName())
                    || !before.getAuthorizedClientRegistrationId().equals(after.getAuthorizedClientRegistrationId())) {
                return Mono.error(new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token"), "Refresh requires the same already-authenticated session"));
            }
            // Login still uses Spring's default fixation protection. Rotating IDs again
            // for parallel refresh requests can leave the browser with an orphaned Redis ID.
            // The refresh handler has already checked issuer, subject and audience.
            session.getAttributes().put(KEY, refreshed);
            return Mono.empty();
        });
    }
}
