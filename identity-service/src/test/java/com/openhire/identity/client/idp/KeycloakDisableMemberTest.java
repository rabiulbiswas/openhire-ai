package com.openhire.identity.client.idp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KeycloakDisableMemberTest {
    @Test
    void disablingAnAccountAlsoRevokesItsSessions() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.add(
                    exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().endsWith("/token")) {
                var bytes = "{\"access_token\":\"fixture-only\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var provider = new KeycloakIdentityProvider(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "staff",
                    "client",
                    "fixture-only");
            provider.disableMember("demo-user");
            assertThat(calls)
                    .contains(
                            "PUT /admin/realms/staff/users/demo-user",
                            "POST /admin/realms/staff/users/demo-user/logout");
        } finally {
            server.stop(0);
        }
    }
}
