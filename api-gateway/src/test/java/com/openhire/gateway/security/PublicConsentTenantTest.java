package com.openhire.gateway.security;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class PublicConsentTenantTest {
    @Test
    void resolvesAnonymousCareerQueriesAndBindsMultipartIntakeToHost() {
        for (var path : new String[] {"/api/v1/public/jobs?page=2", "/api/v1/public/jobs/job"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://fixture.localhost" + path));
            var forwarded = new AtomicReference<ServerWebExchange>();
            new TenantPortalFilters().portalHostBoundary(portals(HttpStatus.OK)).filter(exchange, request -> {
                forwarded.set(request); return Mono.empty();
            }).block();
            assertThat(forwarded.get().getRequest().getQueryParams().getFirst("tenantId")).isEqualTo("dynamic-fixture-tenant");
            if (path.contains("page=")) assertThat(forwarded.get().getRequest().getQueryParams().getFirst("page")).isEqualTo("2");
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("http://fixture.localhost/api/v1/public/applications")
                .header("X-Public-Tenant-Id", "attacker"));
        var forwarded = new AtomicReference<ServerWebExchange>();
        new TenantPortalFilters().portalHostBoundary(portals(HttpStatus.OK)).filter(exchange, request -> {
            forwarded.set(request); return Mono.empty();
        }).block();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Public-Tenant-Id")).isEqualTo("dynamic-fixture-tenant");
    }
    private TenantPortalAccess portals(HttpStatus status) {
        var portals = new TenantPortalAccess(true, "localhost", "oidc", "http://identity-service:8081", "fixture");
        ReflectionTestUtils.setField(
                portals,
                "identity",
                WebClient.builder()
                        .baseUrl("http://identity-service:8081")
                        .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                                .header("Content-Type", "application/json")
                                .body("{\"id\":\"dynamic-fixture-tenant\",\"name\":\"Fixture\",\"slug\":\"fixture\"}")
                                .build()))
                        .build());
        return portals;
    }

    @Test
    void suppliesRegistryTenantForAnonymousConsentAndRemovesSpoofedHeaders() {
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("http://fixture.localhost/api/v1/public/consent")
                        .header("X-Tenant-Id", "attacker-tenant")
                        .header("X-Internal-Secret", "attacker-secret"));
        var forwarded = new AtomicReference<ServerWebExchange>();
        new TenantPortalFilters()
                .portalHostBoundary(portals(HttpStatus.OK))
                .filter(exchange, request -> {
                    forwarded.set(request);
                    return Mono.empty();
                })
                .block();
        assertThat(forwarded.get().getRequest().getQueryParams().get("tenantId"))
                .containsExactly("dynamic-fixture-tenant");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Tenant-Id"))
                .isNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Internal-Secret"))
                .isNull();
        assertThat(exchange.getSession().block().isStarted()).isFalse();
    }

    @Test
    void retainsSameTenantAndRejectsCrossTenantQueryOverrides() {
        for (String id : new String[] {"dynamic-fixture-tenant", "other-tenant"}) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("http://fixture.localhost/api/v1/public/consent?tenantId=" + id));
            var forwarded = new AtomicReference<ServerWebExchange>();
            new TenantPortalFilters()
                    .portalHostBoundary(portals(HttpStatus.OK))
                    .filter(exchange, request -> {
                        forwarded.set(request);
                        return Mono.empty();
                    })
                    .block();
            if (id.equals("other-tenant")) {
                assertThat(forwarded.get()).isNull();
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            } else
                assertThat(forwarded.get().getRequest().getQueryParams().get("tenantId"))
                        .containsExactly(id);
        }
    }

    @Test
    void deniesUnregisteredOrSuspendedTenants() {
        for (var status : new HttpStatus[] {HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN}) {
            var exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("http://fixture.localhost/api/v1/public/consent"));
            assertThatThrownBy(() -> new TenantPortalFilters()
                            .portalHostBoundary(portals(status))
                            .filter(exchange, ignored -> Mono.error(new AssertionError("Must not forward")))
                            .block())
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void platformHostCannotChooseATenantConsentPolicy() {
        var exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("http://admin.localhost/api/v1/public/consent"));
        new TenantPortalFilters()
                .portalHostBoundary(portals(HttpStatus.OK))
                .filter(exchange, ignored -> Mono.error(new AssertionError("Must not forward")))
                .block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void leavesUnrelatedRequestQueriesUnchanged() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://fixture.localhost/api/v1/jobs?status=OPEN"));
        var forwarded = new AtomicReference<ServerWebExchange>();
        new TenantPortalFilters()
                .portalHostBoundary(portals(HttpStatus.OK))
                .filter(exchange, request -> {
                    forwarded.set(request);
                    return Mono.empty();
                })
                .block();
        assertThat(forwarded.get().getRequest().getURI().getRawQuery()).isEqualTo("status=OPEN");
    }
}
