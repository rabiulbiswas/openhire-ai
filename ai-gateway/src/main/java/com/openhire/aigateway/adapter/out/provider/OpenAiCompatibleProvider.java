package com.openhire.aigateway.adapter.out.provider;

import com.openhire.aigateway.application.port.AiPorts.ModelProvider;
import com.openhire.aigateway.domain.AiTypes.ChatRequest;
import com.openhire.aigateway.domain.AiTypes.ChatResult;
import com.openhire.aigateway.domain.AiTypes.EmbedResult;
import com.openhire.aigateway.domain.AiTypes.Health;
import com.openhire.aigateway.domain.AiTypes.ProviderException;
import com.openhire.aigateway.domain.AiTypes.ProviderKind;
import com.openhire.aigateway.domain.Json;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** Any OpenAI-compatible server: /v1/chat/completions with response_format json_schema, /v1/embeddings, /v1/models. */
public class OpenAiCompatibleProvider implements ModelProvider {
    private final String id;
    private final String baseUrl;
    private final boolean local;
    private final RestClient client;
    private final RestClient probe;

    public OpenAiCompatibleProvider(String id, String baseUrl, String apiKey, Duration timeout) {
        this.id = id;
        this.baseUrl = ProviderSupport.trimmed(baseUrl);
        this.local = ProviderSupport.isLocal(baseUrl);
        this.client = ProviderSupport.client(baseUrl, timeout, apiKey);
        this.probe = ProviderSupport.client(baseUrl, Duration.ofSeconds(3), apiKey);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPATIBLE;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public boolean local() {
        return local;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("seed", 42);
        body.put(
                "messages",
                request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList());
        body.put(
                "response_format",
                request.schema() == null
                        ? Map.of("type", "json_object")
                        : Map.of(
                                "type",
                                "json_schema",
                                "json_schema",
                                Map.of("name", "answer", "strict", false, "schema", request.schema())));
        try {
            var reply = client.post()
                    .uri("/v1/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            var choices = Json.list(reply == null ? null : reply.get("choices"));
            if (choices.isEmpty()) throw new ProviderException("No choices returned", false);
            var content = Json.string(
                    Json.map(Json.map(choices.get(0)).get("message")).get("content"));
            if (content.isBlank()) throw new ProviderException("Empty reply", false);
            var usage = Json.map(reply.get("usage"));
            return new ChatResult(
                    content,
                    OllamaProvider.count(usage.get("prompt_tokens")),
                    OllamaProvider.count(usage.get("completion_tokens")));
        } catch (RuntimeException e) {
            throw ProviderSupport.failure(e);
        }
    }

    @Override
    public EmbedResult embed(String model, List<String> inputs) {
        try {
            var reply = client.post()
                    .uri("/v1/embeddings")
                    .body(Map.of("model", model, "input", inputs))
                    .retrieve()
                    .body(Map.class);
            var vectors = new ArrayList<float[]>();
            for (var row : Json.list(reply == null ? null : reply.get("data")))
                vectors.add(OllamaProvider.toFloats(Json.map(row).get("embedding")));
            return new EmbedResult(
                    vectors,
                    OllamaProvider.count(
                            Json.map(reply == null ? null : reply.get("usage")).get("prompt_tokens")));
        } catch (RuntimeException e) {
            throw ProviderSupport.failure(e);
        }
    }

    @Override
    public Health health() {
        try {
            var reply = probe.get().uri("/v1/models").retrieve().body(Map.class);
            var models = new ArrayList<String>();
            for (var item : Json.list(reply == null ? null : reply.get("data")))
                models.add(Json.string(Json.map(item).get("id")));
            return new Health(true, models);
        } catch (RuntimeException e) {
            return new Health(false, List.of());
        }
    }
}
