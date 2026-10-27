package com.openhire.aigateway.client.provider;

import static org.assertj.core.api.Assertions.*;

import com.openhire.aigateway.domain.AiTypes.*;
import com.openhire.aigateway.service.port.AiPorts.ModelProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ProviderTimeoutTest {
    @Test
    void preservesTypedTimeoutsButDoesNotMislabelOtherConnectionFailures() {
        assertThat(ProviderSupport.failure(new ResourceAccessException("read failed", new SocketTimeoutException())))
                .isInstanceOf(ProviderTimeout.class);
        assertThat(ProviderSupport.failure(new IllegalStateException("connection refused")))
                .isNotInstanceOf(ProviderTimeout.class);
    }

    @Test
    void taskSpecificTimeoutWorksForBothProvidersWithoutChangingNormalCalls() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(250);
                    var openAi = exchange.getRequestURI().getPath().contains("completions");
                    if (openAi)
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                .isEqualTo("Bearer test-key");
                    var body = (openAi
                                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                                    : "{\"message\":{\"content\":\"ok\"}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            var providers = List.<ModelProvider>of(
                    new OllamaProvider("ollama", url, Duration.ofMillis(40)),
                    new OpenAiCompatibleProvider("openai", url, "test-key", Duration.ofMillis(40)));
            var request = new ChatRequest("test", List.of(ChatMessage.user("test")), null, 0, 10, 512);
            for (var provider : providers) {
                assertThat(provider.chat(request, Duration.ofSeconds(3)).content())
                        .isEqualTo("ok");
                assertThatThrownBy(() -> provider.chat(request)).isInstanceOf(ProviderTimeout.class);
            }
            server.stop(0);
        } finally {
            server.stop(0);
        }
    }
}
