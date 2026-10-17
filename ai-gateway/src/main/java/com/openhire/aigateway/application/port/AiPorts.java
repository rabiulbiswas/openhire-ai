package com.openhire.aigateway.application.port;

import com.openhire.aigateway.domain.AiSettings;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.ChatRequest;
import com.openhire.aigateway.domain.AiTypes.ChatResult;
import com.openhire.aigateway.domain.AiTypes.EmbedResult;
import com.openhire.aigateway.domain.AiTypes.Health;
import com.openhire.aigateway.domain.AiTypes.Provenance;
import com.openhire.aigateway.domain.AiTypes.ProviderKind;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.domain.Golden;
import com.openhire.aigateway.domain.PromptVersion;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AiPorts {
    private AiPorts() {}

    /** A model server. Implementations map the provider-neutral calls to one wire protocol. */
    public interface ModelProvider {
        String id();

        ProviderKind kind();

        String baseUrl();

        /** True when the server runs on this machine or inside the local network boundary. */
        boolean local();

        ChatResult chat(ChatRequest request);

        EmbedResult embed(String model, List<String> inputs);

        Health health();
    }

    public interface ProviderCatalog {
        List<ModelProvider> all();

        Optional<ModelProvider> find(String id);
    }

    public interface PromptStore {
        void insertIfAbsent(PromptVersion prompt);

        List<PromptVersion> list(String tenantId);

        List<PromptVersion> versions(String tenantId, String key);

        Optional<PromptVersion> find(String tenantId, String id);

        PromptVersion save(PromptVersion prompt);
    }

    public interface SettingsStore {
        Optional<AiSettings> find(String tenantId);

        AiSettings save(AiSettings settings);
    }

    public record TaskUsage(double costInr, long promptTokens, long completionTokens, long requests) {}

    public record Usage(
            double costInr,
            long promptTokens,
            long completionTokens,
            long requests,
            long computeMs,
            Map<String, TaskUsage> byTask) {
        public static Usage none() {
            return new Usage(0, 0, 0, 0, 0, Map.of());
        }
    }

    public interface UsageStore {
        /** Atomically adds to the tenant's totals for the month and returns the totals after the addition. */
        Usage add(
                String tenantId,
                String month,
                AiTask task,
                int promptTokens,
                int completionTokens,
                double costInr,
                long computeMs);

        Usage get(String tenantId, String month);
    }

    public record BudgetAlert(String month, double usedInr, double budgetInr, int percent) {}

    /** What is persisted per AI call. Never holds prompt text, resume text or candidate names. */
    public record RequestRecord(
            String tenantId,
            Provenance provenance,
            String outcome,
            String errorCode,
            Map<String, Object> guardrails,
            String promptHash,
            String purpose,
            BudgetAlert alert) {}

    public record RequestStat(
            String task,
            String provider,
            String model,
            long latencyMs,
            String outcome,
            boolean cached,
            double costInr,
            Instant createdAt) {}

    public interface RequestLog {
        void save(RequestRecord record);

        /** Live (non-evaluation) calls of the tenant since the given instant. */
        List<RequestStat> since(String tenantId, Instant from);
    }

    public interface ResultCache {
        Optional<String> get(String key);

        void put(String key, String value, Duration ttl);
    }

    public interface EvalStore {
        EvalRun save(EvalRun run);

        Optional<EvalRun> find(String tenantId, String id);

        /** The tenant's run that is still marked RUNNING and started after {@code notBefore}, if any. */
        Optional<EvalRun> running(String tenantId, Instant notBefore);
    }

    public interface GoldenDatasets {
        Golden.Dataset load();
    }
}
