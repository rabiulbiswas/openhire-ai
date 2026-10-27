package com.openhire.aigateway.config;

import com.openhire.aigateway.client.provider.OllamaProvider;
import com.openhire.aigateway.client.provider.OpenAiCompatibleProvider;
import com.openhire.aigateway.service.AiDefaults;
import com.openhire.aigateway.service.AiTaskService;
import com.openhire.aigateway.service.CostMeter;
import com.openhire.aigateway.service.EvalGates;
import com.openhire.aigateway.service.EvaluationService;
import com.openhire.aigateway.service.ModelGateway;
import com.openhire.aigateway.service.OpsService;
import com.openhire.aigateway.service.PromptAdminService;
import com.openhire.aigateway.service.PromptRegistry;
import com.openhire.aigateway.service.ProvenanceWriter;
import com.openhire.aigateway.service.ProviderGuards;
import com.openhire.aigateway.service.SettingsService;
import com.openhire.aigateway.service.TaskRouter;
import com.openhire.aigateway.service.port.AiPorts.EvalStore;
import com.openhire.aigateway.service.port.AiPorts.GoldenDatasets;
import com.openhire.aigateway.service.port.AiPorts.ModelProvider;
import com.openhire.aigateway.service.port.AiPorts.PromptStore;
import com.openhire.aigateway.service.port.AiPorts.ProviderCatalog;
import com.openhire.aigateway.service.port.AiPorts.RequestLog;
import com.openhire.aigateway.service.port.AiPorts.ResultCache;
import com.openhire.aigateway.service.port.AiPorts.SettingsStore;
import com.openhire.aigateway.service.port.AiPorts.UsageStore;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AiConfiguration {
    @Bean
    AiDefaults aiDefaults(
            @Value("${openhire.ai.chat-model}") String chatModel,
            @Value("${openhire.ai.embedding-model}") String embeddingModel,
            @Value("${openhire.ai.local-only}") boolean localOnly,
            @Value("${openhire.ai.monthly-budget-inr}") double budget,
            @Value("${openhire.ai.cache-ttl-hours}") long cacheHours,
            @Value("${openhire.ai.context-tokens:8192}") int contextTokens) {
        return new AiDefaults(
                "ollama", chatModel, embeddingModel, localOnly, budget, Duration.ofHours(cacheHours), contextTokens);
    }

    @Bean
    ProviderCatalog providerCatalog(
            @Value("${openhire.ai.ollama-url}") String ollamaUrl,
            @Value("${openhire.ai.openai-compatible-url:}") String openAiUrl,
            @Value("${openhire.ai.openai-compatible-key:}") String openAiKey,
            @Value("${openhire.ai.request-timeout-seconds}") long timeoutSeconds) {
        var timeout = Duration.ofSeconds(timeoutSeconds);
        var all = new ArrayList<ModelProvider>();
        all.add(new OllamaProvider("ollama", ollamaUrl, timeout));
        if (!openAiUrl.isBlank()) all.add(new OpenAiCompatibleProvider("openai", openAiUrl, openAiKey, timeout));
        var list = List.copyOf(all);
        return new ProviderCatalog() {
            @Override
            public List<ModelProvider> all() {
                return list;
            }

            @Override
            public Optional<ModelProvider> find(String id) {
                return list.stream().filter(p -> p.id().equals(id)).findFirst();
            }
        };
    }

    @Bean
    ProviderGuards providerGuards(
            Clock clock,
            @Value("${openhire.ai.circuit-failures}") int failures,
            @Value("${openhire.ai.circuit-open-seconds}") long openSeconds,
            @Value("${openhire.ai.max-concurrency}") int concurrency,
            @Value("${openhire.ai.request-timeout-seconds}") long timeoutSeconds) {
        return new ProviderGuards(
                clock, failures, Duration.ofSeconds(openSeconds), concurrency, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    CostMeter costMeter(UsageStore usage, Clock clock, @Value("${openhire.ai.prices:}") String prices) {
        return new CostMeter(CostMeter.parse(prices), usage, clock);
    }

    @Bean
    TaskRouter taskRouter(SettingsStore settings, ProviderCatalog providers, AiDefaults defaults) {
        return new TaskRouter(settings, providers, defaults);
    }

    @Bean
    PromptRegistry promptRegistry(PromptStore store, Clock clock) {
        return new PromptRegistry(store, clock);
    }

    @Bean
    ProvenanceWriter provenanceWriter(RequestLog log) {
        return new ProvenanceWriter(log);
    }

    @Bean
    ModelGateway modelGateway(
            TaskRouter router,
            PromptRegistry prompts,
            ProviderGuards guards,
            ResultCache cache,
            CostMeter meter,
            ProvenanceWriter provenance,
            AiDefaults defaults,
            Clock clock,
            @Value("${openhire.ai.job-draft-timeout-seconds:180}") long draftTimeoutSeconds) {
        return new ModelGateway(
                router,
                prompts,
                guards,
                cache,
                meter,
                provenance,
                defaults,
                clock,
                Duration.ofSeconds(draftTimeoutSeconds));
    }

    @Bean
    AiTaskService aiTaskService(ModelGateway gateway, Clock clock) {
        return new AiTaskService(gateway, clock);
    }

    @Bean
    SettingsService settingsService(
            TaskRouter router,
            SettingsStore store,
            ProviderCatalog providers,
            ProviderGuards guards,
            CostMeter meter,
            Clock clock,
            AuditRecorder audit) {
        return new SettingsService(router, store, providers, guards, meter, clock, audit);
    }

    @Bean
    PromptAdminService promptAdminService(
            PromptRegistry prompts, EvalStore evals, TaskRouter router, Clock clock, AuditRecorder audit) {
        return new PromptAdminService(prompts, evals, router, clock, audit);
    }

    @Bean
    OpsService opsService(RequestLog log, ProviderCatalog providers, ProviderGuards guards, Clock clock) {
        return new OpsService(log, providers, guards, clock);
    }

    @Bean
    EvalGates evalGates(
            @Value("${openhire.ai.eval.schema-validity-pct:100}") double schema,
            @Value("${openhire.ai.eval.grounded-evidence-pct:95}") double grounded,
            @Value("${openhire.ai.eval.score-in-band-pct:70}") double band,
            @Value("${openhire.ai.eval.counterfactual-delta-p95:1.0}") double delta,
            @Value("${openhire.ai.eval.injection-resistance-pct:100}") double injection,
            @Value("${openhire.ai.eval.p95-latency-ms:20000}") double latency,
            @Value("${openhire.ai.eval.cost-per-assessment-inr:2.0}") double cost,
            @Value("${openhire.ai.eval.criteria-per-assessment:6}") int criteria) {
        return new EvalGates(schema, grounded, band, delta, injection, latency, cost, criteria);
    }

    /** One worker (the model serves one request at a time) and a short queue; extra runs are refused. */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolExecutor evalExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), runnable -> {
            var thread = new Thread(runnable, "ai-eval");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    EvaluationService evaluationService(
            PromptRegistry prompts,
            AiTaskService tasks,
            TaskRouter router,
            EvalStore evals,
            GoldenDatasets datasets,
            EvalGates gates,
            ThreadPoolExecutor evalExecutor,
            Clock clock,
            AuditRecorder audit) {
        return new EvaluationService(prompts, tasks, router, evals, datasets, gates, evalExecutor, clock, audit);
    }
}
