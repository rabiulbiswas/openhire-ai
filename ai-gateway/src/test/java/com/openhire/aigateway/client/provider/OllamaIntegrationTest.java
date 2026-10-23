package com.openhire.aigateway.client.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.aigateway.Fixtures.MemoryCache;
import com.openhire.aigateway.Fixtures.MemoryPrompts;
import com.openhire.aigateway.Fixtures.MemoryRequests;
import com.openhire.aigateway.Fixtures.MemorySettings;
import com.openhire.aigateway.Fixtures.MemoryUsage;
import com.openhire.aigateway.dto.AiViews.ChunkIn;
import com.openhire.aigateway.dto.AiViews.CriterionIn;
import com.openhire.aigateway.dto.AiViews.EmbedRequest;
import com.openhire.aigateway.dto.AiViews.JudgeRequest;
import com.openhire.aigateway.service.AiDefaults;
import com.openhire.aigateway.service.AiTaskService;
import com.openhire.aigateway.service.CostMeter;
import com.openhire.aigateway.service.ModelGateway;
import com.openhire.aigateway.service.PromptRegistry;
import com.openhire.aigateway.service.ProvenanceWriter;
import com.openhire.aigateway.service.ProviderGuards;
import com.openhire.aigateway.service.TaskRouter;
import com.openhire.aigateway.service.port.AiPorts.ModelProvider;
import com.openhire.aigateway.service.port.AiPorts.ProviderCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in: talks to a real Ollama (OLLAMA_BASE_URL, default http://localhost:11434) with the default models. */
@EnabledIfEnvironmentVariable(named = "OPENHIRE_RUN_OLLAMA_TESTS", matches = "true")
class OllamaIntegrationTest {
    private final ModelProvider ollama = new OllamaProvider(
            "ollama",
            System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"),
            Duration.ofSeconds(120));
    private final AiTaskService tasks = tasks();

    private AiTaskService tasks() {
        var clock = Clock.systemUTC();
        var defaults = new AiDefaults(
                "ollama", "qwen2.5:3b-instruct", "nomic-embed-text", true, 1000, Duration.ofHours(1), 8192);
        ProviderCatalog catalog = new ProviderCatalog() {
            public List<ModelProvider> all() {
                return List.of(ollama);
            }

            public Optional<ModelProvider> find(String id) {
                return id.equals("ollama") ? Optional.of(ollama) : Optional.empty();
            }
        };
        var router = new TaskRouter(new MemorySettings(), catalog, defaults);
        var gateway = new ModelGateway(
                router,
                new PromptRegistry(new MemoryPrompts(), clock),
                new ProviderGuards(clock, 4, Duration.ofSeconds(30), 2, Duration.ofSeconds(120)),
                new MemoryCache(),
                new CostMeter(Map.of(), new MemoryUsage(), clock),
                new ProvenanceWriter(new MemoryRequests()),
                defaults,
                clock);
        return new AiTaskService(gateway, clock);
    }

    @Test
    void embedsAndJudgesAgainstTheRealModelServer() {
        assertThat(ollama.health().up()).isTrue();

        var embedded =
                tasks.embed("it", new EmbedRequest(List.of("backend engineer with Kafka", "pastry chef"), "QUERY"));
        assertThat(embedded.dimensions()).isEqualTo(768);
        assertThat(embedded.vectors()).hasSize(2);

        var quote = "Designed an order pipeline on Apache Kafka handling 3 million events per day";
        var judged = tasks.judge(
                "it",
                new JudgeRequest(
                        new CriterionIn("kafka", "Apache Kafka", "MUST", "Hands-on event streaming"),
                        List.of(new ChunkIn(
                                "c1",
                                "Senior Backend Engineer at Acme (2020-2024). " + quote
                                        + " and tuned consumer lag alerts.")),
                        "v1",
                        null));
        assertThat(judged.score()).isBetween(0, 5);
        assertThat(judged.provenance().latencyMs()).isPositive();
        assertThat(judged.provenance().promptTokens()).isPositive();
        // Whatever the small model decides, every returned quote exists in the chunk.
        judged.evidence().forEach(e -> assertThat(quote + " and tuned consumer lag alerts")
                .contains(e.quote().substring(0, Math.min(20, e.quote().length()))));
    }
}
