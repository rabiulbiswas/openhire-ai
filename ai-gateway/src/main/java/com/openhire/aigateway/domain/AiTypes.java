package com.openhire.aigateway.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AiTypes {
    private AiTypes() {}

    public enum ProviderKind {
        OLLAMA,
        OPENAI_COMPATIBLE
    }

    public enum ProviderStatus {
        UP,
        DOWN,
        CIRCUIT_OPEN
    }

    public enum PromptStatus {
        DRAFT,
        SHADOW,
        ACTIVE,
        RETIRED
    }

    public enum EvalStatus {
        NONE,
        PASSED,
        FAILED
    }

    public enum RunStatus {
        RUNNING,
        DONE,
        FAILED
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public record ChatMessage(String role, String content) {
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    /** A provider-neutral chat call. {@code schema} is the JSON schema the reply must follow (null for any JSON). */
    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            Map<String, Object> schema,
            double temperature,
            int maxTokens,
            int contextTokens) {}

    public record ChatResult(String content, int promptTokens, int completionTokens) {}

    public record EmbedResult(List<float[]> vectors, int promptTokens) {}

    public record Health(boolean up, List<String> models) {}

    public record ModelRef(String provider, String model) {}

    public record Provenance(
            String requestId,
            String task,
            String provider,
            String model,
            String promptKey,
            Integer promptVersion,
            long latencyMs,
            int promptTokens,
            int completionTokens,
            double costInr,
            boolean cached,
            String traceId,
            Instant createdAt) {}

    /** A model server failed or refused. {@code circuit} says whether it should count against the circuit breaker. */
    public static class ProviderException extends RuntimeException {
        private final boolean circuit;

        public ProviderException(String message, boolean circuit, Throwable cause) {
            super(message, cause);
            this.circuit = circuit;
        }

        public ProviderException(String message, boolean circuit) {
            this(message, circuit, null);
        }

        public boolean circuit() {
            return circuit;
        }
    }

    public static class ProviderTimeout extends ProviderException {
        public ProviderTimeout(Throwable cause) {
            super("The model response timed out", true, cause);
        }
    }

    /** The model reply was well formed but a guard rejected it; the gateway may retry once with feedback. */
    public static class GuardRejection extends RuntimeException {
        public GuardRejection(String message) {
            super(message);
        }
    }
}
