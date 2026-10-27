package com.openhire.platform.security;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TenantStaffDirectoryTest {
    @Test
    void usesTenantScopedAuthenticatedDirectoryAndRequiresTheExactRole() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.ArrayList<String>();
        server.createContext("/api/v1/internal/approval-members", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Service-Secret"))
                    .isEqualTo("test-secret");
            var json = exchange.getRequestURI().getQuery().equals("tenantId=tenant-a")
                    ? "[{\"id\":\"manager\",\"email\":\"manager@example.test\",\"displayName\":\"Manager\",\"roles\":[\"HIRING_MANAGER\"]}]"
                    : "[]";
            var bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var directory = new TenantStaffDirectory(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-secret");
            assertThat(directory
                            .require("tenant-a", "manager", "Hiring manager")
                            .id())
                    .isEqualTo("manager");
            assertThatThrownBy(() -> directory.require("tenant-b", "manager", "HIRING_MANAGER"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> directory.require("tenant-a", "manager", "HR_ADMIN"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(requests).contains("/api/v1/internal/approval-members?tenantId=tenant-b");
        } finally {
            server.stop(0);
        }
    }
}
