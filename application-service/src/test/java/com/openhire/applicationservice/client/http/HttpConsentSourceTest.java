package com.openhire.applicationservice.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpConsentSourceTest {
    private static HttpServer server(int status, String body, AtomicReference<String> seen) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/consent/active", exchange -> {
            seen.set(exchange.getRequestURI().getQuery() + "|"
                    + exchange.getRequestHeaders().getFirst("X-Internal-Service-Secret"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void readsVersionAndTextWithTheServiceSecret() throws Exception {
        var seen = new AtomicReference<String>();
        var server = server(200, "{\"version\":\"v4\",\"text\":\"Hello\",\"extra\":1}", seen);
        try {
            var source = new HttpConsentSource(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "s3cret");
            var consent = source.active("tenant-1").orElseThrow();
            assertThat(consent.version()).isEqualTo("v4");
            assertThat(consent.text()).isEqualTo("Hello");
            assertThat(seen.get()).isEqualTo("tenantId=tenant-1&purpose=PROCESSING|s3cret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorResponseYieldsEmpty() throws Exception {
        var server = server(500, "{}", new AtomicReference<>());
        try {
            var source = new HttpConsentSource(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "x");
            assertThat(source.active("t")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableServiceYieldsEmpty() {
        assertThat(new HttpConsentSource("http://127.0.0.1:1", "x").active("t")).isEmpty();
    }
}
