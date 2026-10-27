package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class TokenRelayRefreshConfigurationTest {
    @Test
    void expiredTokenRefreshIgnoresBusinessScopesAndPersistsRenewedClient() {
        var registration = ClientRegistration.withRegistrationId("tenant")
                .clientId("tenant")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://tenant.localhost/callback")
                .scope("openid", "profile")
                .authorizationUri("https://identity.test/auth")
                .tokenUri("https://identity.test/token")
                .build();
        var repository = new WebSessionServerOAuth2AuthorizedClientRepository();
        var manager = new DefaultReactiveOAuth2AuthorizedClientManager(
                new InMemoryReactiveClientRegistrationRepository(registration), repository);
        var provider = new RefreshTokenReactiveOAuth2AuthorizedClientProvider();
        var refreshes = new AtomicInteger();
        provider.setAccessTokenResponseClient(request -> {
            assertThat(request.getScopes()).isEmpty();
            refreshes.incrementAndGet();
            return Mono.just(OAuth2AccessTokenResponse.withToken("renewed-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(300)
                    .scopes(Set.of("openid", "profile"))
                    .refreshToken("renewed-refresh")
                    .build());
        });
        manager.setAuthorizedClientProvider(provider);
        TokenRelayRefreshConfiguration.relayContextAttributes().postProcessAfterInitialization(manager, "manager");
        var principal = new UsernamePasswordAuthenticationToken("user", "unused");
        for (String scope : new String[] {"waiting_on_me", "requested_by_me", "completed", "arbitrary"}) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("http://tenant.localhost/api/v1/approvals?scope=" + scope));
            var expired = new OAuth2AuthorizedClient(
                    registration,
                    "user",
                    new OAuth2AccessToken(
                            OAuth2AccessToken.TokenType.BEARER,
                            "expired",
                            Instant.now().minusSeconds(600),
                            Instant.now().minusSeconds(1)),
                    new OAuth2RefreshToken("refresh", Instant.now().minusSeconds(600)));
            repository.saveAuthorizedClient(expired, principal, exchange).block();
            // TokenRelay passes the exchange in Reactor context, not authorize-request attributes.
            var request = OAuth2AuthorizeRequest.withClientRegistrationId("tenant")
                    .principal(principal)
                    .build();
            assertThat(manager.authorize(request)
                            .contextWrite(context -> context.put(ServerWebExchange.class, exchange))
                            .block()
                            .getAccessToken()
                            .getTokenValue())
                    .isEqualTo("renewed-token");
            assertThat(repository
                            .loadAuthorizedClient("tenant", principal, exchange)
                            .block()
                            .getAccessToken()
                            .getTokenValue())
                    .isEqualTo("renewed-token");
            manager.authorize(request)
                    .contextWrite(context -> context.put(ServerWebExchange.class, exchange))
                    .block();
        }
        assertThat(refreshes).hasValue(4);
    }
}
