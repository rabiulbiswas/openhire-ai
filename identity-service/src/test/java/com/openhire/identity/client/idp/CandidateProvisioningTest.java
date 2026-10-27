package com.openhire.identity.client.idp;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class CandidateProvisioningTest {
    @Test void createsOnlyVerifiedCandidateWithPasswordAtProviderAndEnablesAfterRoleAssignment() throws Exception {
        var writes = new CopyOnWriteArrayList<Document>();
        var server = fixture(false, writes); server.start();
        try {
            var provider = provider(server);
            assertThat(provider.registerVerifiedCandidate("tenant", "new@example.test", "New Candidate", "Fixture candidate password!", "opaque-registration")).isEqualTo("new-id");
            var creation = writes.stream().filter(row -> row.getString("path").endsWith("/users")).findFirst().orElseThrow().get("body", Document.class);
            assertThat(creation).containsEntry("emailVerified", true).containsEntry("enabled", false);
            assertThat(creation.getList("credentials", Document.class).getFirst()).containsEntry("temporary", false).containsEntry("type", "password");
            var roleWrite = writes.stream().filter(row -> row.getString("path").endsWith("/role-mappings/realm")).findFirst().orElseThrow();
            assertThat(roleWrite.getString("raw")).contains("CANDIDATE").doesNotContain("RECRUITER", "ORG_ADMIN");
            assertThat(writes.getLast().get("body", Document.class)).containsEntry("enabled", true);
        } finally { server.stop(0); }
    }
    @Test void cannotOverwriteAnExistingIdentityOrResetItsPassword() throws Exception {
        var writes = new CopyOnWriteArrayList<Document>(); var server = fixture(true, writes); server.start();
        try {
            assertThatThrownBy(() -> provider(server).registerVerifiedCandidate("tenant", "staff@example.test", "Staff", "Fixture candidate password!", "attacker-registration"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
            assertThat(writes).isEmpty();
        } finally { server.stop(0); }
    }
    private KeycloakIdentityProvider provider(HttpServer server) { return new KeycloakIdentityProvider(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), "staff", "fixture-client", "fixture-secret"); }
    private HttpServer fixture(boolean existing, List<Document> writes) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath(); var method = exchange.getRequestMethod();
            var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response = null; int status = 200;
            if (path.endsWith("/token")) response = "{\"access_token\":\"fixture-only\"}";
            else if (method.equals("GET") && path.endsWith("/users")) response = existing ? "[{\"id\":\"staff\",\"attributes\":{\"organization_id\":[\"tenant\"]}}]" : "[]";
            else if (method.equals("GET") && path.endsWith("/available")) response = "[{\"id\":\"candidate-role\",\"name\":\"CANDIDATE\"},{\"id\":\"recruiter-role\",\"name\":\"RECRUITER\"}]";
            else if (method.equals("GET")) response = "[]";
            else {
                var value = new Document("method", method).append("path", path).append("raw", raw);
                if (raw.startsWith("{")) value.put("body", Document.parse(raw));
                writes.add(value); status = path.endsWith("/users") ? 201 : 204;
                if (status == 201) exchange.getResponseHeaders().set("Location", "http://127.0.0.1/admin/realms/staff/users/new-id");
            }
            if (response == null) exchange.sendResponseHeaders(status, -1);
            else { var bytes = response.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            exchange.close();
        });
        return server;
    }
}
