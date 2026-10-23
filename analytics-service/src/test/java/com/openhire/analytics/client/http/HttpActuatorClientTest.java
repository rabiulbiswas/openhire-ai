package com.openhire.analytics.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpActuatorClientTest {
    private HttpServer server;
    private String base;
    private final HttpActuatorClient client =
            new HttpActuatorClient(JsonMapper.builder().build());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void healthyServiceIsUp() {
        respond("/actuator/health", 200, "{\"status\":\"UP\"}");
        server.start();

        var health = client.health(base);

        assertThat(health.reachable()).isTrue();
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.responseMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aServiceAnswering503WithADownBodyIsReachableButDown() {
        respond("/actuator/health", 503, "{\"status\":\"DOWN\"}");
        server.start();

        var health = client.health(base);

        assertThat(health.reachable()).isTrue();
        assertThat(health.status()).isEqualTo("DOWN");
    }

    @Test
    void aClosedPortIsUnreachableNotAnError() {
        var health = client.health("http://127.0.0.1:1");

        assertThat(health.reachable()).isFalse();
        assertThat(health.status()).isEqualTo("UNREACHABLE");
        assertThat(client.httpMetrics("http://127.0.0.1:1", false)).isEmpty();
    }

    @Test
    void httpServerRequestMetricsAreReadFromTheMeasurements() {
        respond(
                "/actuator/metrics/http.server.requests",
                200,
                "{\"name\":\"http.server.requests\",\"measurements\":[{\"statistic\":\"COUNT\",\"value\":120.0},"
                        + "{\"statistic\":\"TOTAL_TIME\",\"value\":9.5},{\"statistic\":\"MAX\",\"value\":0.75}]}");
        server.start();

        var metrics = client.httpMetrics(base, false).orElseThrow();

        assertThat(metrics.count()).isEqualTo(120);
        assertThat(metrics.totalSeconds()).isEqualTo(9.5);
        assertThat(metrics.maxSeconds()).isEqualTo(0.75);
    }

    @Test
    void metricsThatAreNotExposedOrHaveNoMatchingTagAreAbsent() {
        respond("/actuator/metrics/http.server.requests", 404, "{}");
        server.start();

        assertThat(client.httpMetrics(base, true)).isEmpty();
    }
}
