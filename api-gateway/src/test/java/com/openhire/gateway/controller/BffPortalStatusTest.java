package com.openhire.gateway.controller;

import static org.assertj.core.api.Assertions.*;

import com.openhire.gateway.security.TenantPortalAccess;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class BffPortalStatusTest {
    @Test
    void publicBrandingExposesOnlyIdAndNameWithoutStartingASession() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://codlogicx.localhost/bff/public-tenant"));
        var result = controller(HttpStatus.OK, true).publicTenant(exchange).block();
        assertThat(result).containsOnlyKeys("id", "name");
        assertThat(result).containsEntry("id", "456").containsEntry("name", "Codlogicx");
        assertThat(exchange.getSession().block().isStarted()).isFalse();
        var platform = MockServerWebExchange.from(MockServerHttpRequest.get("http://admin.localhost/bff/public-tenant"));
        assertThatThrownBy(() -> controller(HttpStatus.OK, true).publicTenant(platform).block()).isInstanceOf(ResponseStatusException.class);
    }
    private BffSessionController controller(HttpStatus status, boolean enabled) {
        var portals = new TenantPortalAccess(enabled, "localhost", "oidc", "http://identity-service:8081", "fixture");
        ReflectionTestUtils.setField(portals, "sameHostAuth", true);
        var client = WebClient.builder()
                .baseUrl("http://identity-service:8081")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header("Content-Type", "application/json")
                        .body("{\"id\":\"456\",\"name\":\"Codlogicx\",\"slug\":\"codlogicx\"}")
                        .build()))
                .build();
        ReflectionTestUtils.setField(portals, "identity", client);
        var controller = new BffSessionController(
                "oidc", "", URI.create("http://localhost/login"), URI.create("http://localhost/login"));
        ReflectionTestUtils.setField(controller, "portals", portals);
        return controller;
    }

    @Test
    void allowsAnActiveRegisteredTenantWithoutAUserSession() {
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("http://codlogicx.localhost/bff/portal-status"));
        assertThat(controller(HttpStatus.OK, true)
                        .portalStatus(exchange)
                        .block()
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange.getSession().block().isStarted()).isFalse();
    }

    @Test
    void refusesUnknownOrSuspendedTenantsEvenWithoutTheGatewayFilter() {
        for (var status : new HttpStatus[] {HttpStatus.NOT_FOUND, HttpStatus.BAD_REQUEST}) {
            var exchange =
                    MockServerWebExchange.from(MockServerHttpRequest.get("http://unknown.localhost/bff/portal-status"));
            assertThatThrownBy(() ->
                            controller(status, true).portalStatus(exchange).block())
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Test
    void refusesPortalDisabledAndUntrustedHosts() {
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("http://codlogicx.localhost/bff/portal-status"));
        assertThat(controller(HttpStatus.OK, false)
                        .portalStatus(exchange)
                        .block()
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var untrusted = MockServerWebExchange.from(MockServerHttpRequest.get("http://evil.example/bff/portal-status"));
        assertThatThrownBy(() ->
                        controller(HttpStatus.OK, true).portalStatus(untrusted).block())
                .isInstanceOf(ResponseStatusException.class);
    }
}
