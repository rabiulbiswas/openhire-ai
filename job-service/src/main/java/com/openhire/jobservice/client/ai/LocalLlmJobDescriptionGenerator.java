package com.openhire.jobservice.client.ai;

import com.openhire.jobservice.dto.request.AiJobDraftRequest;
import com.openhire.jobservice.dto.response.AiJobDraftResponse;
import com.openhire.jobservice.service.port.JobDescriptionGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalLlmJobDescriptionGenerator implements JobDescriptionGenerator {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final String apiStyle;

    public LocalLlmJobDescriptionGenerator(
            ObjectMapper objectMapper,
            @Value("${openhire.llm.base-url:http://localhost:11434}") String baseUrl,
            @Value("${openhire.llm.model:qwen3:8b}") String model,
            @Value("${openhire.llm.api-style:ollama}") String apiStyle) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(60));
        this.client =
                RestClient.builder().baseUrl(baseUrl).requestFactory(requests).build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.apiStyle = apiStyle;
    }

    @Override
    public AiJobDraftResponse generate(AiJobDraftRequest request) {
        try {
            var prompt = prompt(request);
            String content = apiStyle.equalsIgnoreCase("openai") ? callOpenAiCompatible(prompt) : callOllama(prompt);
            var result = objectMapper.readValue(stripCodeFence(content), DraftPayload.class);
            return new AiJobDraftResponse(
                    result.description(), safe(result.biasFlags()), safeRounds(result.suggestedInterviewPlan()), model);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "The local LLM is unavailable or returned invalid JSON. Check OPENHIRE_LLM_BASE_URL and OPENHIRE_LLM_MODEL.",
                    exception);
        }
    }

    private String callOllama(String prompt) {
        var response = client.post()
                .uri("/api/chat")
                .body(Map.of(
                        "model",
                        model,
                        "stream",
                        false,
                        "format",
                        "json",
                        "messages",
                        List.of(Map.of("role", "user", "content", prompt))))
                .retrieve()
                .body(JsonNode.class);
        return response.path("message").path("content").asText();
    }

    private String callOpenAiCompatible(String prompt) {
        var response = client.post()
                .uri("/v1/chat/completions")
                .body(Map.of(
                        "model",
                        model,
                        "temperature",
                        0.2,
                        "messages",
                        List.of(Map.of("role", "user", "content", prompt))))
                .retrieve()
                .body(JsonNode.class);
        return response.path("choices").path(0).path("message").path("content").asText();
    }

    private String prompt(AiJobDraftRequest request) {
        return """
                You draft inclusive, factual job descriptions. Use only the supplied facts. Do not invent benefits,
                qualifications or compensation. Return JSON only with keys: description, biasFlags (array), and
                suggestedInterviewPlan (array of objects with id, name, durationMin, interviewerNames).

                Title: %s
                Team: %s
                Level: %s
                Must-have skills: %s
                Tone: %s
                """
                .formatted(
                        request.title(),
                        request.team(),
                        request.level(),
                        String.join(", ", request.mustHaveSkills()),
                        request.tone());
    }

    private String stripCodeFence(String value) {
        var trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private List<AiJobDraftResponse.InterviewRound> safeRounds(List<DraftRound> values) {
        if (values == null) return List.of();
        return values.stream()
                .map(value -> new AiJobDraftResponse.InterviewRound(
                        value.id(), value.name(), value.durationMin(), safe(value.interviewerNames())))
                .toList();
    }

    private record DraftPayload(String description, List<String> biasFlags, List<DraftRound> suggestedInterviewPlan) {}

    private record DraftRound(String id, String name, int durationMin, List<String> interviewerNames) {}
}
