package com.openhire.aigateway.client.provider;

import com.openhire.aigateway.domain.AiTypes.ChatRequest;
import com.openhire.aigateway.domain.AiTypes.ChatResult;
import com.openhire.aigateway.domain.AiTypes.EmbedResult;
import com.openhire.aigateway.domain.AiTypes.Health;
import com.openhire.aigateway.domain.AiTypes.ProviderException;
import com.openhire.aigateway.domain.AiTypes.ProviderKind;
import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.service.port.AiPorts.ModelProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/** Ollama's native API: /api/chat with a JSON schema in {@code format}, /api/embed, /api/tags. */
public class OllamaProvider implements ModelProvider {
    private final String id;
    private final String baseUrl;
    private final boolean local;
    private final RestClient client;
    private final RestClient probe;

    public OllamaProvider(String id, String baseUrl, Duration timeout) {
        this.id = id;
        this.baseUrl = ProviderSupport.trimmed(baseUrl);
        this.local = ProviderSupport.isLocal(baseUrl);
        this.client = ProviderSupport.client(baseUrl, timeout, null);
        this.probe = ProviderSupport.client(baseUrl, Duration.ofSeconds(2), null);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OLLAMA;
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
        return chat(request, client);
    }

    @Override
    public ChatResult chat(ChatRequest request, Duration timeout) {
        return chat(request, ProviderSupport.client(baseUrl, timeout, null));
    }

    private ChatResult chat(ChatRequest request, RestClient http) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("stream", false);
        body.put("format", request.schema() == null ? "json" : request.schema());
        body.put("keep_alive", "30m");
        body.put(
                "messages",
                request.messages().stream()
                        .map(m -> Map.of("role", m.role(), "content", m.content()))
                        .toList());
        body.put(
                "options",
                Map.of(
                        "temperature", request.temperature(),
                        "seed", 42,
                        "num_predict", request.maxTokens(),
                        "num_ctx", request.contextTokens()));
        try {
            var reply = http.post().uri("/api/chat").body(body).retrieve().body(Map.class);
            var message = Json.map(reply == null ? null : reply.get("message"));
            var content = Json.string(message.get("content"));
            if (content.isBlank()) throw new ProviderException("Empty reply", false);
            return new ChatResult(content, count(reply.get("prompt_eval_count")), count(reply.get("eval_count")));
        } catch (RuntimeException e) {
            throw ProviderSupport.failure(e);
        }
    }

    @Override
    public EmbedResult embed(String model, List<String> inputs) {
        try {
            var reply = client.post()
                    .uri("/api/embed")
                    .body(Map.of("model", model, "input", inputs, "truncate", true, "keep_alive", "30m"))
                    .retrieve()
                    .body(Map.class);
            var vectors = new ArrayList<float[]>();
            for (var row : Json.list(reply == null ? null : reply.get("embeddings"))) vectors.add(toFloats(row));
            return new EmbedResult(vectors, count(reply == null ? null : reply.get("prompt_eval_count")));
        } catch (RuntimeException e) {
            throw ProviderSupport.failure(e);
        }
    }

    @Override
    public Health health() {
        try {
            var reply = probe.get().uri("/api/tags").retrieve().body(Map.class);
            var models = new ArrayList<String>();
            for (var item : Json.list(reply == null ? null : reply.get("models")))
                models.add(Json.string(Json.map(item).get("name")));
            return new Health(true, models);
        } catch (RuntimeException e) {
            return new Health(false, List.of());
        }
    }

    static int count(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    static float[] toFloats(Object row) {
        var list = Json.list(row);
        var out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = ((Number) list.get(i)).floatValue();
        return out;
    }
}
