package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import reactor.core.publisher.Mono;

class DocumentationAccessTest {
    @Test
    void docsAreHiddenWhenDisabledOrInPermissiveLocalMode() {
        for (var guard : List.of(new DocumentationAccess(false, "oidc"), new DocumentationAccess(true, "local"))) {
            for (var path : List.of(
                    DocumentationAccess.UI, "/v3/api-docs", "/openapi/config", "/openapi/services/job-service")) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                var called = new AtomicBoolean();
                guard.filter(exchange, next -> {
                            called.set(true);
                            return Mono.empty();
                        })
                        .block();
                assertThat(called).isFalse();
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
        }
    }

    @Test
    void enabledDocsContinueToSecurityButOtherApplicationRoutesAreUnaffected() {
        var called = new AtomicBoolean();
        new DocumentationAccess(true, "oidc")
                .filter(MockServerWebExchange.from(MockServerHttpRequest.get(DocumentationAccess.UI)), next -> {
                    called.set(true);
                    return Mono.empty();
                })
                .block();
        assertThat(called).isTrue();
        assertThat(DocumentationAccess.documentationPath("/api/v1/jobs")).isFalse();
    }

    @Test
    void staffOnlyAndAnonymousCannotUseCandidateRegistration() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var user = new DefaultOAuth2User(authorities, Map.of("sub", "fixture"), "sub");
        assertThat(DocumentationAccess.staffSession(null)).isFalse();
        assertThat(DocumentationAccess.staffSession(
                        new OAuth2AuthenticationToken(user, authorities, "openhire-candidate")))
                .isFalse();
        assertThat(DocumentationAccess.staffSession(new OAuth2AuthenticationToken(user, authorities, "openhire-staff")))
                .isTrue();
    }

    @Test
    void uiUsesFixedStaffLoginAndSpecsReturn401WithoutRedirect() {
        var ui = MockServerWebExchange.from(MockServerHttpRequest.get(DocumentationAccess.UI));
        DocumentationAccess.startStaffLogin(ui).block();
        assertThat(ui.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(ui.getResponse().getHeaders().getLocation().toString())
                .isEqualTo("/oauth2/authorization/openhire-staff");
        assertThat((Object) ui.getSession().block().getAttribute(DocumentationAccess.LOGIN_DESTINATION))
                .isEqualTo(Boolean.TRUE);
        var spec = MockServerWebExchange.from(MockServerHttpRequest.get("/openapi/services/job-service"));
        DocumentationAccess.startStaffLogin(spec).block();
        assertThat(spec.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(spec.getResponse().getHeaders().getLocation()).isNull();
    }

    @Test
    void staffReturnsToDocsOnceAndNormalLoginKeepsApplicationDestination() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var user = new DefaultOAuth2User(authorities, Map.of("sub", "fixture"), "sub");
        var staff = new OAuth2AuthenticationToken(user, authorities, "openhire-staff");
        var docs = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/openhire-staff"));
        docs.getSession().block().getAttributes().put(DocumentationAccess.LOGIN_DESTINATION, true);
        DocumentationAccess.afterLogin(
                        new org.springframework.security.web.server.WebFilterExchange(docs, next -> Mono.empty()),
                        staff,
                        "http://localhost:5173/")
                .block();
        assertThat(docs.getResponse().getHeaders().getLocation().toString()).isEqualTo(DocumentationAccess.UI);
        assertThat(docs.getSession().block().getAttributes()).doesNotContainKey(DocumentationAccess.LOGIN_DESTINATION);
        var normal = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/openhire-staff"));
        DocumentationAccess.afterLogin(
                        new org.springframework.security.web.server.WebFilterExchange(normal, next -> Mono.empty()),
                        staff,
                        "http://localhost:5173/")
                .block();
        assertThat(normal.getResponse().getHeaders().getLocation().toString()).isEqualTo("http://localhost:5173/");
    }
}
