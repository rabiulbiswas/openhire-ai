package com.openhire.analytics.adapter.out.http;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PrometheusSamplesTest {
    @Test
    void acceptsFreshSamplesRejectsStaleSamplesAndEscapesTenantSelector() throws Exception {
        var now = Instant.parse("2026-10-15T12:00:00Z");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/api/v1/query", exchange -> {
            query.set(java.net.URLDecoder.decode(
                    exchange.getRequestURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8));
            var response = ("{\"status\":\"success\",\"data\":{\"result\":["
                            + "{\"metric\":{\"slo_id\":\"public-api-availability\"},\"value\":[" + now.getEpochSecond()
                            + ",\"99.9\"]},"
                            + "{\"metric\":{\"slo_id\":\"application-api-latency\"},\"value\":["
                            + (now.getEpochSecond() - 121) + ",\"120\"]}]}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            var samples = new PrometheusSamples(
                    "http://127.0.0.1:" + server.getAddress().getPort(), Clock.fixed(now, ZoneOffset.UTC));
            assertThat(samples.current("tenant\"x")).containsOnlyKeys("public-api-availability");
            assertThat(query.get()).contains("tenant_id=\"tenant\\\"x\"").contains("scope=\"tenant\"");
            samples.current(null);
            assertThat(query.get()).contains("scope=\"global\"").contains("tenant_id=\"\"");
            assertThat(new PrometheusSamples("", Clock.systemUTC()).current("a"))
                    .isEmpty();
        } finally {
            server.stop(0);
        }
    }
}
