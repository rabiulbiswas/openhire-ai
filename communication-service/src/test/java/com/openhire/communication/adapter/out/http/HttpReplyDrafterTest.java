package com.openhire.communication.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.communication.application.port.CommunicationPorts.DraftRequest;
import com.openhire.communication.application.port.CommunicationPorts.DraftTurn;
import com.openhire.communication.application.port.CommunicationPorts.DraftingUnavailableException;
import com.openhire.support.Access;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpReplyDrafterTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> tenant = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private HttpReplyDrafter start(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/internal/ai/message-draft", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tenant.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var access = new Access("local", "demo-org", "test-secret");
        return new HttpReplyDrafter(
                access, "http://127.0.0.1:" + server.getAddress().getPort(), 2000);
    }

    private static DraftRequest request() {
        return new DraftRequest(
                "Priya", "Backend Engineer", List.of(new DraftTurn("them", "Can we move to Tuesday?")), "EMAIL");
    }

    @Test
    void sendsTheReplyPurposeWithOnlyTheFirstNameAndReturnsTheText() throws Exception {
        var drafter = start(200, "{\"text\":\"Tuesday works.\",\"provenance\":{}}");

        assertThat(drafter.draftReply("t1", request())).isEqualTo("Tuesday works.");

        assertThat(tenant.get()).isEqualTo("t1");
        assertThat(body.get())
                .contains("\"purpose\":\"REPLY\"")
                .contains("\"candidateFirstName\":\"Priya\"")
                .contains("\"from\":\"them\"")
                .doesNotContain("@")
                .doesNotContain("phone");
    }

    @Test
    void aBudgetOrAvailabilityFailureBecomesDraftingUnavailable() throws Exception {
        assertThatThrownBy(() -> start(503, "{\"code\":\"AI_UNAVAILABLE\"}").draftReply("t1", request()))
                .isInstanceOf(DraftingUnavailableException.class);
        server.stop(0);
        assertThatThrownBy(
                        () -> start(402, "{\"code\":\"AI_BUDGET_EXHAUSTED\"}").draftReply("t1", request()))
                .isInstanceOf(DraftingUnavailableException.class);
    }

    @Test
    void anUnreachableGatewayBecomesDraftingUnavailable() {
        var access = new Access("local", "demo-org", "test-secret");
        var drafter = new HttpReplyDrafter(access, "http://127.0.0.1:1", 500);

        assertThatThrownBy(() -> drafter.draftReply("t1", request())).isInstanceOf(DraftingUnavailableException.class);
    }
}
