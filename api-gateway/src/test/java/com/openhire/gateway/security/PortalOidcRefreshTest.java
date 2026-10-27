package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.*;
import org.springframework.security.oauth2.core.oidc.user.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class PortalOidcRefreshTest {
    @Test
    @SuppressWarnings("unchecked")
    void realOidcRefreshUsesPortalDecoderAndUserServiceAndPersistsSession() {
        var now = Instant.now();
        String issuer = "http://codelogicx.localhost/auth/realms/openhire-staff";
        var registration = ClientRegistration.withRegistrationId("openhire-tenant")
                .clientId("openhire-tenant")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://codelogicx.localhost/callback")
                .scope("openid", "profile")
                .issuerUri(issuer)
                .authorizationUri(issuer + "/auth")
                .tokenUri("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/token")
                .build();
        var claims = Map.<String, Object>of(
                "iss",
                issuer,
                "sub",
                "member",
                "aud",
                List.of("openhire-tenant"),
                "tenant_id",
                "tenant-a",
                "email_verified",
                true,
                "realm_access",
                Map.of("roles", List.of("RECRUITER")));
        var oldId = new OidcIdToken("old-id", now.minusSeconds(600), now.minusSeconds(1), claims);
        var oldUser = new DefaultOidcUser(List.of(new OidcUserAuthority(oldId)), oldId);
        var principal = new OAuth2AuthenticationToken(oldUser, oldUser.getAuthorities(), "openhire-tenant");
        var refreshedId = new OidcIdToken("new-id", now, now.plusSeconds(300), claims);
        var refreshedUser = new DefaultOidcUser(List.of(new OidcUserAuthority(refreshedId)), refreshedId);
        ReactiveJwtDecoderFactory<ClientRegistration> decoders = mock(ReactiveJwtDecoderFactory.class);
        ReactiveJwtDecoder decoder = mock(ReactiveJwtDecoder.class);
        when(decoders.createDecoder(registration)).thenReturn(decoder);
        when(decoder.decode("new-id"))
                .thenReturn(Mono.just(Jwt.withTokenValue("new-id")
                        .header("alg", "RS256")
                        .claims(values -> values.putAll(claims))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build()));
        ReactiveOAuth2UserService<OidcUserRequest, OidcUser> users = mock(ReactiveOAuth2UserService.class);
        when(users.loadUser(any())).thenReturn(Mono.just(refreshedUser));
        var provider = new LocalPortalOidcConfiguration()
                .portalRefreshProvider(
                        request -> Mono.just(OAuth2AccessTokenResponse.withToken("new-access")
                                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                                .expiresIn(300)
                                .scopes(Set.of("openid", "profile"))
                                .refreshToken("new-refresh")
                                .additionalParameters(Map.of("id_token", "new-id"))
                                .build()),
                        decoders,
                        users);
        var repository = new WebSessionServerOAuth2AuthorizedClientRepository();
        var manager = new DefaultReactiveOAuth2AuthorizedClientManager(
                new InMemoryReactiveClientRegistrationRepository(registration), repository);
        manager.setAuthorizedClientProvider(provider);
        TokenRelayRefreshConfiguration.relayContextAttributes().postProcessAfterInitialization(manager, "manager");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://codelogicx.localhost/api/v1/jobs?scope=waiting_on_me"));
        var browserSession = exchange.getSession().block();
        var anonymousId = browserSession.getId();
        new WebSessionServerSecurityContextRepository()
                .save(exchange, new org.springframework.security.core.context.SecurityContextImpl(principal))
                .block();
        var authenticatedId = browserSession.getId();
        assertThat(authenticatedId).isNotEqualTo(anonymousId);
        repository
                .saveAuthorizedClient(
                        new OAuth2AuthorizedClient(
                                registration,
                                "member",
                                new OAuth2AccessToken(
                                        OAuth2AccessToken.TokenType.BEARER,
                                        "old-access",
                                        now.minusSeconds(600),
                                        now.minusSeconds(1)),
                                new OAuth2RefreshToken("old-refresh", now.minusSeconds(600))),
                        principal,
                        exchange)
                .block();
        var result = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("openhire-tenant")
                        .principal(principal)
                        .build())
                .contextWrite(context -> context.put(ServerWebExchange.class, exchange))
                .block();
        assertThat(result.getAccessToken().getTokenValue()).isEqualTo("new-access");
        assertThat(browserSession.getId()).isEqualTo(authenticatedId);
        verify(decoders).createDecoder(registration);
        verify(users)
                .loadUser(argThat(
                        request -> request.getIdToken().getIssuer().toString().equals(issuer)
                                && request.getAccessToken().getTokenValue().equals("new-access")));
        var context =
                new WebSessionServerSecurityContextRepository().load(exchange).block();
        assertThat(((OidcUser) context.getAuthentication().getPrincipal())
                        .getIdToken()
                        .getTokenValue())
                .isEqualTo("new-id");
        assertThat(repository
                        .loadAuthorizedClient("openhire-tenant", principal, exchange)
                        .block()
                        .getAccessToken()
                        .getTokenValue())
                .isEqualTo("new-access");
    }
}
