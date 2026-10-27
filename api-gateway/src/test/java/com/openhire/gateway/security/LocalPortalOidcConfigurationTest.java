package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.*;

class LocalPortalOidcConfigurationTest {
    private LocalPortalOidcConfiguration.PortalRegistrations registrations() {
        return new LocalPortalOidcConfiguration.PortalRegistrations(new InMemoryReactiveClientRegistrationRepository(
                client("openhire-tenant"), client("openhire-platform")));
    }

    private ClientRegistration client(String id) {
        return ClientRegistration.withRegistrationId(id)
                .clientId(id)
                .clientSecret("synthetic-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("http://admin.localhost/auth/realms/openhire-staff/protocol/openid-connect/auth")
                .tokenUri("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/token")
                .jwkSetUri("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/certs")
                .userInfoUri("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .build();
    }

    @Test
    void registrationsRemainIsolatedAcrossPortalContexts() {
        var registrations = registrations();
        for (String slug : new String[] {"codlogicx", "another"}) {
            var result = registrations
                    .findByRegistrationId("openhire-tenant")
                    .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://" + slug + ".localhost"))
                    .block();
            assertThat(result.getProviderDetails().getIssuerUri())
                    .isEqualTo("http://" + slug + ".localhost/auth/realms/openhire-staff");
            assertThat(result.getProviderDetails().getTokenUri()).startsWith("http://keycloak:8080/auth/");
        }
    }

    @Test
    void refusesMissingContextAndWrongClient() {
        assertThatThrownBy(() ->
                        registrations().findByRegistrationId("openhire-tenant").block())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> registrations()
                        .findByRegistrationId("openhire-platform")
                        .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://codlogicx.localhost"))
                        .block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizationRequestKeepsHostCallbackAndPkce() {
        var resolver = new DefaultServerOAuth2AuthorizationRequestResolver(registrations());
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://codlogicx.localhost/oauth2/authorization/openhire-tenant"));
        var result = resolver.resolve(exchange)
                .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://codlogicx.localhost"))
                .block();
        assertThat(result.getAuthorizationUri()).startsWith("http://codlogicx.localhost/auth/");
        assertThat(result.getRedirectUri()).isEqualTo("http://codlogicx.localhost/login/oauth2/code/openhire-tenant");
        assertThat(result.getState()).isNotBlank();
        assertThat(result.getAdditionalParameters()).containsEntry("code_challenge_method", "S256");
    }

    @Test
    void backchannelHeadersAreBoundToPortalAndEndpointIsPinned() {
        var captured = new java.util.concurrent.atomic.AtomicReference<
                org.springframework.web.reactive.function.client.ClientRequest>();
        var client = LocalPortalOidcConfiguration.portalClient()
                .mutate()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return reactor.core.publisher.Mono.just(
                            org.springframework.web.reactive.function.client.ClientResponse.create(
                                            org.springframework.http.HttpStatus.OK)
                                    .body("fixture")
                                    .build());
                })
                .build();
        client.get()
                .uri("http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/userinfo")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Prefix", "/evil")
                .header("X-Forwarded-Ssl", "on")
                .retrieve()
                .bodyToMono(String.class)
                .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://codlogicx.localhost"))
                .block();
        assertThat(captured.get().headers().getFirst("X-Forwarded-Host")).isEqualTo("codlogicx.localhost");
        assertThat(captured.get().headers().getFirst("X-Forwarded-Prefix")).isNull();
        assertThat(captured.get().headers().getFirst("X-Forwarded-Ssl")).isNull();
        assertThat(captured.get().url().getHost()).isEqualTo("keycloak");
        assertThatThrownBy(() -> client.get()
                        .uri("http://evil.example/auth/realms/openhire-staff/protocol/openid-connect/token")
                        .retrieve()
                        .bodyToMono(String.class)
                        .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://codlogicx.localhost"))
                        .block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springWrapsRepositoryAndPublishesSecurityClients() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                    "fixture",
                                    java.util.Map.of(
                                            "openhire.portals.same-host-auth", "true",
                                            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                                                    "http://keycloak:8080/auth/realms/openhire-staff/protocol/openid-connect/certs")));
            context.registerBean(
                    "registrations",
                    ReactiveClientRegistrationRepository.class,
                    () -> new InMemoryReactiveClientRegistrationRepository(
                            client("openhire-tenant"), client("openhire-platform")));
            context.register(LocalPortalOidcConfiguration.class);
            context.refresh();
            assertThat(context.getBean(ReactiveClientRegistrationRepository.class))
                    .isInstanceOf(LocalPortalOidcConfiguration.PortalRegistrations.class);
            assertThat(context.getBean(org.springframework.security.oauth2.jwt.ReactiveJwtDecoder.class))
                    .isNotNull();
            assertThat(context.getBean("portalCodeClient")).isNotNull();
            assertThat(context.getBean("portalRefreshClient")).isNotNull();
            assertThat(context.getBean(
                            org.springframework.security.oauth2.client
                                    .RefreshTokenReactiveOAuth2AuthorizedClientProvider.class))
                    .isSameAs(context.getBean("portalRefreshProvider"));
            assertThat(context.getBean("portalUserService")).isNotNull();
            assertThat(context.getBean("portalAuthorizedClients"))
                    .isInstanceOf(
                            org.springframework.security.oauth2.client.web.server
                                    .WebSessionServerOAuth2AuthorizedClientRepository.class);
            assertThat(context.getBean("portalIdTokenDecoders")).isNotNull();
        }
    }

    @Test
    void idTokenDecoderCacheIsIsolatedByIssuerNotJustSharedClientId() {
        var factory = new LocalPortalOidcConfiguration().portalIdTokenDecoders();
        var first = registrations()
                .findByRegistrationId("openhire-tenant")
                .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://codlogicx.localhost"))
                .block();
        var second = registrations()
                .findByRegistrationId("openhire-tenant")
                .contextWrite(c -> c.put(LocalPortalOidcConfiguration.ORIGIN, "http://another.localhost"))
                .block();
        assertThat(factory.createDecoder(first)).isSameAs(factory.createDecoder(first));
        assertThat(factory.createDecoder(first)).isNotSameAs(factory.createDecoder(second));
    }

    @Test
    void authorizedClientsAreStoredInTheIndividualBrowserSession() {
        var repository = new LocalPortalOidcConfiguration().portalAuthorizedClients();
        var auth = new org.springframework.security.authentication.TestingAuthenticationToken(
                "same-subject", "fixture", "ROLE_RECRUITER");
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("http://codlogicx.localhost/"));
        var second = MockServerWebExchange.from(MockServerHttpRequest.get("http://another.localhost/"));
        var token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "synthetic-token",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60));
        var authorized = new org.springframework.security.oauth2.client.OAuth2AuthorizedClient(
                client("openhire-tenant"), auth.getName(), token);
        repository.saveAuthorizedClient(authorized, auth, first).block();
        assertThat(repository
                        .loadAuthorizedClient("openhire-tenant", auth, first)
                        .block())
                .isNotNull();
        assertThat(repository
                        .loadAuthorizedClient("openhire-tenant", auth, second)
                        .block())
                .isNull();
    }
}
