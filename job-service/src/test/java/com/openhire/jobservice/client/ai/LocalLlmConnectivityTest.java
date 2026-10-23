package com.openhire.jobservice.client.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.jobservice.dto.request.AiJobDraftRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

class LocalLlmConnectivityTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENHIRE_LLM_CONNECTIVITY_TEST", matches = "true")
    void configuredLocalModelReturnsStructuredDraft() {
        var generator = new LocalLlmJobDescriptionGenerator(
                new ObjectMapper(),
                System.getenv().getOrDefault("OPENHIRE_LLM_BASE_URL", "http://localhost:11434"),
                System.getenv().getOrDefault("OPENHIRE_LLM_MODEL", "qwen3:8b"),
                System.getenv().getOrDefault("OPENHIRE_LLM_API_STYLE", "ollama"));

        var result = generator.generate(new AiJobDraftRequest(
                "Backend Engineer", "Platform", "L4", java.util.List.of("Java", "Kafka"), "clear"));

        assertThat(result.description()).isNotBlank();
    }
}
