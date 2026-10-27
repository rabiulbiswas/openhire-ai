package com.openhire.gateway.security;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.oidc.userinfo.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.userinfo.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

/** Per-request registration, isolated by host-only BFF cookies, saved state and PKCE. Local HTTP only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "openhire.portals.same-host-auth", havingValue = "true")
public class LocalPortalOidcConfiguration {
    public static final String ORIGIN = LocalPortalOidcConfiguration.class.getName() + ".origin";

    @Bean
    static BeanPostProcessor portalRegistrations() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof ReactiveClientRegistrationRepository repository
                        ? new PortalRegistrations(repository)
                        : bean;
            }
        };
    }

    static final class PortalRegistrations
            implements ReactiveClientRegistrationRepository, Iterable<ClientRegistration> {
        private final ReactiveClientRegistrationRepository delegate;

        PortalRegistrations(ReactiveClientRegistrationRepository delegate) {
            this.delegate = delegate;
        }

        public Mono<ClientRegistration> findByRegistrationId(String id) {
            return Mono.deferContextual(
                    context -> delegate.findByRegistrationId(id).map(base -> {
                        if (!Set.of("openhire-platform", "openhire-tenant").contains(id))
                            throw new IllegalArgumentException("Use the portal-specific client");
                        String origin = LocalPortalIssuerValidator.origin(context.get(ORIGIN));
                        String expected = URI.create(origin).getHost().equals("admin.localhost")
                                ? "openhire-platform"
                                : "openhire-tenant";
                        if (!expected.equals(id)) throw new IllegalArgumentException("Wrong portal client");
                        String issuer = origin + "/auth/realms/openhire-staff";
                        var metadata = new HashMap<>(base.getProviderDetails().getConfigurationMetadata());
                        metadata.put("issuer", issuer);
                        return ClientRegistration.withClientRegistration(base)
                                .issuerUri(issuer)
                                .authorizationUri(issuer + "/protocol/openid-connect/auth")
                                .providerConfigurationMetadata(metadata)
                                .build();
                    }));
        }

        @SuppressWarnings("unchecked")
        public Iterator<ClientRegistration> iterator() {
            return delegate instanceof Iterable<?> values
                    ? ((Iterable<ClientRegistration>) values).iterator()
                    : Collections.emptyIterator();
        }
    }

    static WebClient portalClient() {
        return WebClient.builder()
                .filter((request, next) -> Mono.deferContextual(context -> {
                    var origin = URI.create(LocalPortalIssuerValidator.origin(context.get(ORIGIN)));
                    // The endpoint remains the configured private Keycloak URL; never fetch an issuer-supplied URL.
                    var target = request.url();
                    if (!"http".equals(target.getScheme())
                            || !"keycloak".equals(target.getHost())
                            || target.getPort() != 8080
                            || !target.getPath().startsWith("/auth/realms/openhire-staff/protocol/openid-connect/"))
                        return Mono.error(new IllegalArgumentException("Unexpected authentication endpoint"));
                    return next.exchange(ClientRequest.from(request)
                            .headers(headers -> {
                                headers.remove("Forwarded");
                                headers.remove("X-Forwarded-Prefix");
                                headers.remove("X-Forwarded-Ssl");
                                headers.set("X-Forwarded-Host", origin.getHost());
                                headers.set("X-Forwarded-Proto", "http");
                                headers.set("X-Forwarded-Port", "80");
                            })
                            .build());
                }))
                .build();
    }

    @Bean
    ReactiveOAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> portalCodeClient() {
        var client = new WebClientReactiveAuthorizationCodeTokenResponseClient();
        client.setWebClient(portalClient());
        return client;
    }

    @Bean
    ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> portalRefreshClient() {
        var client = new WebClientReactiveRefreshTokenTokenResponseClient();
        client.setWebClient(portalClient());
        return client;
    }

    @Bean
    org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider portalRefreshProvider(
            ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> portalRefreshClient,
            ReactiveJwtDecoderFactory<ClientRegistration> portalIdTokenDecoders,
            ReactiveOAuth2UserService<OidcUserRequest, OidcUser> portalUserService) {
        // Spring 7.1 refreshes OIDC user information as well as the access token.
        // Its default handler does not inherit the portal-aware login collaborators:
        // an internal-host UserInfo request would reject a tenant-host token issuer.
        var refreshedUser =
                new org.springframework.security.oauth2.client
                        .RefreshOidcUserReactiveOAuth2AuthorizationSuccessHandler();
        refreshedUser.setJwtDecoderFactory(portalIdTokenDecoders);
        refreshedUser.setUserService(portalUserService);
        refreshedUser.setServerSecurityContextRepository(new ExistingSessionRefreshContextRepository());
        var provider =
                new org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(portalRefreshClient);
        provider.setAuthorizationSuccessHandler(refreshedUser);
        return provider;
    }

    @Bean
    ReactiveOAuth2UserService<OidcUserRequest, OidcUser> portalUserService() {
        var userInfo = new DefaultReactiveOAuth2UserService();
        userInfo.setWebClient(portalClient());
        var oidc = new OidcReactiveOAuth2UserService();
        oidc.setOauth2UserService(userInfo);
        return oidc;
    }

    @Bean
    ReactiveJwtDecoder portalJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwks) {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), new LocalPortalIssuerValidator()));
        return decoder;
    }

    @Bean
    org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
            portalAuthorizedClients() {
        // A global (registrationId, subject) store would mix clients for the same user across portal sessions.
        return new org.springframework.security.oauth2.client.web.server
                .WebSessionServerOAuth2AuthorizedClientRepository();
    }

    @Bean
    ReactiveJwtDecoderFactory<ClientRegistration> portalIdTokenDecoders() {
        // Spring's default ID-token decoder factory caches by registrationId, which tenants share.
        var cache = new LinkedHashMap<String, ReactiveJwtDecoder>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ReactiveJwtDecoder> entry) {
                return size() > 128;
            }
        };
        return registration -> {
            var issuer = registration.getProviderDetails().getIssuerUri();
            LocalPortalIssuerValidator.origin(issuer);
            var key = issuer + "\n" + registration.getClientId();
            synchronized (cache) {
                return cache.computeIfAbsent(
                        key,
                        ignored ->
                                new org.springframework.security.oauth2.client.oidc.authentication
                                                .ReactiveOidcIdTokenDecoderFactory()
                                        .createDecoder(registration));
            }
        };
    }
}
