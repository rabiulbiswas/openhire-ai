package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import reactor.core.publisher.Mono;

class ExistingSessionRefreshContextRepositoryTest {
    private SecurityContextImpl identity(String subject, String registration) {
        var id = new OidcIdToken("synthetic", Instant.now(), Instant.now().plusSeconds(300), Map.of("sub", subject));
        var user = new DefaultOidcUser(List.of(new OidcUserAuthority(id)), id);
        return new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), registration));
    }

    @Test
    void parallelVerifiedRefreshesKeepTheExistingSessionId() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://tenant.localhost/api/v1/jobs"));
        new WebSessionServerSecurityContextRepository()
                .save(exchange, identity("member", "tenant"))
                .block();
        var id = exchange.getSession().block().getId();
        var repository = new ExistingSessionRefreshContextRepository();
        Mono.when(
                        repository.save(exchange, identity("member", "tenant")),
                        repository.save(exchange, identity("member", "tenant")))
                .block();
        assertThat(exchange.getSession().block().getId()).isEqualTo(id);
        assertThat(repository.load(exchange).block().getAuthentication().getName())
                .isEqualTo("member");
    }

    @Test
    void cannotEstablishALoginOrSwitchIdentityOrClient() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://tenant.localhost/api/v1/jobs"));
        var repository = new ExistingSessionRefreshContextRepository();
        assertThatThrownBy(() ->
                        repository.save(exchange, identity("member", "tenant")).block())
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        new WebSessionServerSecurityContextRepository()
                .save(exchange, identity("member", "tenant"))
                .block();
        assertThatThrownBy(() -> repository
                        .save(exchange, identity("other-member", "tenant"))
                        .block())
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> repository
                        .save(exchange, identity("member", "platform"))
                        .block())
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
    }
}
