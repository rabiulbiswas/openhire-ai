package com.openhire.identity.client.idp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class EnterpriseDirectoryHttpTest {
    @Test
    void managedProvisionDeactivateAndTenantOwnershipUseRealHttpSerialization() throws Exception {
        var calls = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path + " " + body);
            String user =
                    "{\"id\":\"remote\",\"attributes\":{\"organization_id\":[\"tenant\"],\"openhire_scim_id\":[\"stable\"]}}";
            String response = path.endsWith("/token")
                    ? "{\"access_token\":\"test-token\"}"
                    : method.equals("GET") && path.endsWith("/users")
                            ? "[" + user + "]"
                            : method.equals("GET") ? user : null;
            if (response == null) exchange.sendResponseHeaders(204, -1);
            else {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            var directory = new KeycloakEnterpriseDirectory(
                    RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "staff",
                    "test-client",
                    "test-only",
                    "keycloak");
            assertEquals("remote", directory.provision("tenant", "stable", "staff@example.com", "Staff"));
            assertTrue(calls.stream()
                    .anyMatch(c -> c.startsWith("POST /admin/realms/staff/users ")
                            && c.contains("\"enabled\":false")
                            && c.contains("scim.stable")));
            directory.update("tenant", "remote", "Staff", false);
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /admin/realms/staff/users/remote/logout")));
            int before = calls.size();
            assertThrows(ResponseStatusException.class, () -> directory.update("other", "remote", "Intruder", true));
            assertFalse(calls.subList(before, calls.size()).stream().anyMatch(c -> c.startsWith("PUT ")));
        } finally {
            server.stop(0);
        }
    }
}
