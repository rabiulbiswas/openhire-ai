package com.openhire.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.server.MockWebSession;
import reactor.core.publisher.Mono;

class BffSessionControllerTest {
    @Test
    void csrfEndpointReadsTheSecurityRequestAttribute() {
        var controller = new BffSessionController(
                "oidc", "demo-org", URI.create("http://localhost/login"), URI.create("http://localhost/candidate"));
        var token = new org.springframework.security.web.server.csrf.DefaultCsrfToken(
                "X-XSRF-TOKEN", "_csrf", "fixture-token");
        org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller)
                .webFilter((exchange, chain) -> {
                    exchange.getAttributes()
                            .put(
                                    org.springframework.security.web.server.csrf.CsrfToken.class.getName(),
                                    Mono.just(token));
                    return chain.filter(exchange);
                })
                .build()
                .get()
                .uri("/bff/csrf")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .isEqualTo("fixture-token");
    }

    @Test
    void logoutWithoutAuthenticationInvalidatesSessionAndReturnsToSignIn() {
        var signIn = URI.create("http://localhost:5176/login");
        var controller = new BffSessionController(
                "oidc", "demo-org", signIn, URI.create("http://localhost:5176/candidate-sign-in"));
        var session = new MockWebSession();

        var response = controller.browserLogout(Mono.empty(), session).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isEqualTo(signIn);
        assertThat(session.isStarted()).isFalse();
    }
}
