package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.ChatMessage;
import com.openhire.aigateway.domain.AiTypes.ChatRequest;
import com.openhire.aigateway.domain.AiTypes.GuardRejection;
import com.openhire.aigateway.domain.AiTypes.Provenance;
import com.openhire.aigateway.domain.AiTypes.ProviderException;
import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.domain.PromptVersion;
import com.openhire.aigateway.domain.SchemaValidator;
import com.openhire.aigateway.domain.Text;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import com.openhire.aigateway.exception.AiExceptions.BudgetExhausted;
import com.openhire.aigateway.service.TaskRouter.Route;
import com.openhire.aigateway.service.port.AiPorts.BudgetAlert;
import com.openhire.aigateway.service.port.AiPorts.ResultCache;
import com.openhire.support.Records;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.MDC;

/**
 * The one path every model call takes: route, prompt, token budget, cache, bulkhead and circuit breaker, provider
 * call, schema check with one repair retry, task guard, cost metering and provenance. Failures fail soft as
 * {@link AiUnavailable} so callers can fall back to rules.
 */
public class ModelGateway {
    private static final String REPAIR =
            "Your previous reply was rejected: %s Reply again with corrected JSON only, following the instructions.";

    private final TaskRouter router;
    private final PromptRegistry prompts;
    private final ProviderGuards guards;
    private final ResultCache cache;
    private final CostMeter meter;
    private final ProvenanceWriter provenance;
    private final AiDefaults defaults;
    private final Clock clock;
    private final java.time.Duration draftTimeout;

    public ModelGateway(
            TaskRouter router,
            PromptRegistry prompts,
            ProviderGuards guards,
            ResultCache cache,
            CostMeter meter,
            ProvenanceWriter provenance,
            AiDefaults defaults,
            Clock clock) {
        this(router, prompts, guards, cache, meter, provenance, defaults, clock, java.time.Duration.ofSeconds(180));
    }

    public ModelGateway(
            TaskRouter router,
            PromptRegistry prompts,
            ProviderGuards guards,
            ResultCache cache,
            CostMeter meter,
            ProvenanceWriter provenance,
            AiDefaults defaults,
            Clock clock,
            java.time.Duration draftTimeout) {
        this.router = router;
        this.prompts = prompts;
        this.guards = guards;
        this.cache = cache;
        this.meter = meter;
        this.provenance = provenance;
        this.defaults = defaults;
        this.clock = clock;
        if (draftTimeout.isNegative() || draftTimeout.isZero() || draftTimeout.toSeconds() > 240)
            throw new IllegalArgumentException("Job draft timeout must be between 1 and 240 seconds");
        this.draftTimeout = draftTimeout;
    }

    /**
     * A chat task. {@code guard} turns the schema-valid JSON into the task result or throws {@link GuardRejection};
     * {@code describe} reports guardrail results for provenance. A non-null {@code prompt} forces a version
     * (evaluation); {@code eval} bypasses the cache and marks the record as an evaluation call.
     */
    public record Call<T>(
            String tenantId,
            AiTask task,
            PromptVersion prompt,
            String requestKey,
            Map<String, String> variables,
            int maxTokens,
            boolean eval,
            Function<Map<String, Object>, T> guard,
            Function<T, Map<String, Object>> describe) {}

    public record Outcome<T>(T value, Provenance provenance, int attempts, int latencyMs) {}

    private static final class State {
        final String requestId = UUID.randomUUID().toString();
        final long started;
        Route route;
        PromptVersion prompt;
        String promptHash;
        int promptTokens;
        int completionTokens;
        long providerMs;
        boolean cached;
        boolean providerCalled;
        String outcome = "OK";
        String errorCode;

        State(long started) {
            this.started = started;
        }
    }

    public <T> Outcome<T> chat(Call<T> call) {
        var state = new State(clock.millis());
        T value = null;
        int attempts = 1;
        Map<String, Object> described = Map.of();
        RuntimeException failure = null;
        double budget = defaults.monthlyBudgetInr();
        try {
            var settings = router.effective(call.tenantId());
            budget = settings.monthlyBudgetInr();
            var prompt = call.prompt() != null
                    ? call.prompt()
                    : prompts.select(call.tenantId(), call.task().promptKey(), call.requestKey());
            state.prompt = prompt;
            var system = prompt.renderSystem(call.variables());
            var user = prompt.renderUser(call.variables());
            state.promptHash = Records.hash(system, user);
            int limit = defaults.contextTokens() - call.maxTokens() - 64;
            int estimate = Text.estimateTokens(system) + Text.estimateTokens(user);
            if (estimate > limit)
                throw new IllegalArgumentException(
                        "The input is too long for the model (about " + estimate + " tokens, limit " + limit + ")");
            var schema = prompt.schema();
            RuntimeException last = null;
            for (var route : router.routes(call.tenantId(), call.task())) {
                state.route = route;
                var key = Records.hash(
                        call.tenantId(), route.model(), prompt.key(), String.valueOf(prompt.version()), system, user);
                if (call.task().cacheable() && !call.eval()) {
                    var hit = cachedValue(key, call.guard());
                    if (hit.isPresent()) {
                        state.cached = true;
                        value = hit.get();
                        break;
                    }
                }
                try {
                    var run = run(call, state, route, settings.monthlyBudgetInr(), system, user, schema);
                    value = run.value();
                    attempts = run.attempts();
                    if (call.task().cacheable() && !call.eval())
                        cache.put("aicache:" + key, run.json(), defaults.cacheTtl());
                    break;
                } catch (AiUnavailable | BudgetExhausted e) {
                    last = e;
                }
            }
            if (value == null) throw last != null ? last : new AiUnavailable("The model could not be reached");
            described = call.describe().apply(value);
        } catch (RuntimeException e) {
            failure = e;
            classify(state, e);
        }
        var record = finish(call.tenantId(), call.task(), state, described, call.eval() ? "EVAL" : "LIVE", budget);
        if (failure != null) throw failure;
        return new Outcome<>(value, record, attempts, (int) record.latencyMs());
    }

    private record Run<T>(T value, String json, int attempts) {}

    private <T> Run<T> run(
            Call<T> call,
            State state,
            Route route,
            double budget,
            String system,
            String user,
            Map<String, Object> schema) {
        var provider = route.provider();
        if (!provider.local()) meter.ensureBudget(call.tenantId(), budget);
        var guard = guards.of(provider.id());
        var messages = new ArrayList<>(List.of(ChatMessage.system(system), ChatMessage.user(user)));
        for (int attempt = 1; ; attempt++) {
            var request = new ChatRequest(
                    route.model(), List.copyOf(messages), schema, 0, call.maxTokens(), defaults.contextTokens());
            long t0 = clock.millis();
            com.openhire.aigateway.domain.AiTypes.ChatResult result;
            try (var permit = call.task() == AiTask.JD_DRAFT
                    ? guard.bulkhead()
                            .acquire(
                                    remainingDraftTime(state).compareTo(java.time.Duration.ofSeconds(15)) < 0
                                            ? remainingDraftTime(state)
                                            : java.time.Duration.ofSeconds(15))
                    : guard.bulkhead().acquire()) {
                if (!guard.breaker().tryAcquire())
                    throw new AiUnavailable(
                            "The model provider " + provider.id() + " is temporarily disabled after repeated failures");
                try {
                    result = call.task() == AiTask.JD_DRAFT
                            ? provider.chat(request, remainingDraftTime(state))
                            : provider.chat(request);
                    guard.breaker().onSuccess();
                } catch (ProviderException e) {
                    if (e.circuit()) guard.breaker().onFailure();
                    else guard.breaker().onSuccess();
                    if (call.task() == AiTask.JD_DRAFT
                            && e instanceof com.openhire.aigateway.domain.AiTypes.ProviderTimeout)
                        throw new com.openhire.aigateway.exception.AiExceptions.AiTimeout();
                    throw new AiUnavailable("The model provider " + provider.id() + " failed: " + e.getMessage(), e);
                }
            } finally {
                state.providerMs += clock.millis() - t0;
            }
            state.providerCalled = true;
            state.promptTokens += result.promptTokens();
            state.completionTokens += result.completionTokens();
            String feedback;
            try {
                var json = Json.parseReply(result.content());
                var errors = SchemaValidator.validate(schema, json);
                if (errors.isEmpty()) {
                    try {
                        var value = call.guard().apply(json);
                        state.outcome = attempt > 1 ? "REPAIRED" : "OK";
                        return new Run<>(value, Json.write(json), attempt);
                    } catch (GuardRejection rejected) {
                        state.outcome = "GUARDRAIL_REJECTED";
                        feedback = rejected.getMessage();
                    }
                } else {
                    state.outcome = "INVALID_OUTPUT";
                    feedback = "The JSON does not match the required shape: " + String.join("; ", errors) + ".";
                }
            } catch (IllegalArgumentException notJson) {
                state.outcome = "INVALID_OUTPUT";
                feedback = "The reply was not a valid JSON object.";
            }
            if (attempt >= 2) {
                if (call.task() == AiTask.JD_DRAFT)
                    throw new com.openhire.aigateway.exception.AiExceptions.AiInvalidResponse();
                throw new AiUnavailable("The model did not produce a valid answer");
            }
            messages.add(ChatMessage.assistant(result.content()));
            messages.add(ChatMessage.user(REPAIR.formatted(feedback)));
        }
    }

    private <T> Optional<T> cachedValue(String key, Function<Map<String, Object>, T> guard) {
        try {
            return cache.get("aicache:" + key).map(Json::readMap).map(guard);
        } catch (RuntimeException unusable) {
            return Optional.empty();
        }
    }

    private java.time.Duration remainingDraftTime(State state) {
        long remaining = draftTimeout.toMillis() - Math.max(0, clock.millis() - state.started);
        if (remaining <= 0) throw new com.openhire.aigateway.exception.AiExceptions.AiTimeout();
        return java.time.Duration.ofMillis(remaining);
    }

    private void classify(State state, RuntimeException e) {
        if (e instanceof BudgetExhausted) {
            state.outcome = "BUDGET_EXHAUSTED";
            state.errorCode = "AI_BUDGET_EXHAUSTED";
        } else if (e instanceof com.openhire.aigateway.exception.AiExceptions.AiTimeout) {
            state.outcome = "TIMED_OUT";
            state.errorCode = "AI_TIMEOUT";
        } else if (e instanceof com.openhire.aigateway.exception.AiExceptions.AiInvalidResponse) {
            state.errorCode = "AI_INVALID_RESPONSE";
        } else if (e instanceof AiUnavailable) {
            if (state.outcome.equals("OK")) state.outcome = "PROVIDER_ERROR";
            state.errorCode = "AI_UNAVAILABLE";
        } else {
            state.outcome = "REJECTED_INPUT";
            state.errorCode = "INVALID_REQUEST";
        }
    }

    private Provenance finish(
            String tenantId, AiTask task, State state, Map<String, Object> guardrails, String purpose, double budget) {
        var model = state.route == null ? "" : state.route.model();
        var providerId = state.route == null ? "" : state.route.provider().id();
        boolean local = state.route == null || state.route.provider().local();
        double cost = state.providerCalled ? meter.cost(model, local, state.promptTokens, state.completionTokens) : 0;
        BudgetAlert alert = null;
        if (state.providerCalled)
            try {
                alert = meter.charge(
                        tenantId, task, budget, state.promptTokens, state.completionTokens, cost, state.providerMs);
            } catch (RuntimeException ignored) {
                // Metering must not turn a good answer into an error.
            }
        var traceId = Optional.ofNullable(MDC.get("traceId")).orElse(state.requestId);
        var record = new Provenance(
                state.requestId,
                task.key(),
                providerId,
                model,
                state.prompt == null ? task.promptKey() : state.prompt.key(),
                state.prompt == null ? null : state.prompt.version(),
                clock.millis() - state.started,
                state.promptTokens,
                state.completionTokens,
                cost,
                state.cached,
                traceId,
                clock.instant());
        provenance.write(
                tenantId,
                record,
                state.cached && state.outcome.equals("OK") ? "CACHED" : state.outcome,
                state.errorCode,
                guardrails,
                state.promptHash,
                purpose,
                alert);
        return record;
    }

    // ---- embeddings ----

    public record Embedded(List<float[]> vectors, int dimensions, Provenance provenance) {}

    public Embedded embed(String tenantId, List<String> inputs, String inputType) {
        var state = new State(clock.millis());
        double budget = defaults.monthlyBudgetInr();
        try {
            var settings = router.effective(tenantId);
            budget = settings.monthlyBudgetInr();
            RuntimeException last = null;
            for (var route : router.routes(tenantId, AiTask.EMBEDDINGS)) {
                state.route = route;
                var prefix = prefix(route.model(), inputType);
                var vectors = new float[inputs.size()][];
                var missing = new ArrayList<Integer>();
                var keys = new ArrayList<String>();
                for (int i = 0; i < inputs.size(); i++) {
                    var key = "aicache:" + Records.hash(tenantId, route.model(), "embed", prefix + inputs.get(i));
                    keys.add(key);
                    var hit = cache.get(key).map(ModelGateway::decode);
                    if (hit.isPresent()) vectors[i] = hit.get();
                    else missing.add(i);
                }
                state.cached = missing.isEmpty();
                if (!missing.isEmpty()) {
                    try {
                        var texts = missing.stream()
                                .map(i -> prefix + inputs.get(i))
                                .toList();
                        var result = callEmbed(state, route, settings.monthlyBudgetInr(), texts, tenantId);
                        for (int j = 0; j < missing.size(); j++) {
                            vectors[missing.get(j)] = result.vectors().get(j);
                            cache.put(
                                    keys.get(missing.get(j)),
                                    encode(result.vectors().get(j)),
                                    defaults.cacheTtl());
                        }
                        state.promptTokens += result.promptTokens();
                    } catch (AiUnavailable | BudgetExhausted e) {
                        last = e;
                        state.cached = false;
                        continue;
                    }
                }
                var list = List.of(vectors);
                var record =
                        finish(tenantId, AiTask.EMBEDDINGS, state, Map.of("inputs", inputs.size()), "LIVE", budget);
                return new Embedded(list, vectors.length == 0 ? 0 : vectors[0].length, record);
            }
            throw last != null ? last : new AiUnavailable("The embedding model could not be reached");
        } catch (RuntimeException e) {
            classify(state, e);
            finish(tenantId, AiTask.EMBEDDINGS, state, Map.of(), "LIVE", budget);
            throw e;
        }
    }

    private com.openhire.aigateway.domain.AiTypes.EmbedResult callEmbed(
            State state, Route route, double budget, List<String> texts, String tenantId) {
        var provider = route.provider();
        if (!provider.local()) meter.ensureBudget(tenantId, budget);
        var guard = guards.of(provider.id());
        long t0 = clock.millis();
        try (var permit = guard.bulkhead().acquire()) {
            if (!guard.breaker().tryAcquire())
                throw new AiUnavailable(
                        "The model provider " + provider.id() + " is temporarily disabled after repeated failures");
            try {
                var result = provider.embed(route.model(), texts);
                guard.breaker().onSuccess();
                if (result.vectors().size() != texts.size())
                    throw new AiUnavailable("The embedding model returned the wrong number of vectors");
                state.providerCalled = true;
                return result;
            } catch (ProviderException e) {
                if (e.circuit()) guard.breaker().onFailure();
                else guard.breaker().onSuccess();
                throw new AiUnavailable("The model provider " + provider.id() + " failed: " + e.getMessage(), e);
            }
        } finally {
            state.providerMs += clock.millis() - t0;
        }
    }

    /** nomic-embed-text was trained with task prefixes; other models get none. */
    static String prefix(String model, String inputType) {
        if (!model.startsWith("nomic-embed") || inputType == null) return "";
        return "QUERY".equalsIgnoreCase(inputType) ? "search_query: " : "search_document: ";
    }

    static String encode(float[] vector) {
        var buffer = ByteBuffer.allocate(vector.length * 4);
        for (var f : vector) buffer.putFloat(f);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String text) {
        var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(text));
        var out = new float[buffer.remaining() / 4];
        for (int i = 0; i < out.length; i++) out[i] = buffer.getFloat();
        return out;
    }

    /** Helper for tasks: a stable request key for rollout bucketing. */
    public static String keyOf(Object... parts) {
        var strings = new String[parts.length];
        for (int i = 0; i < parts.length; i++) strings[i] = String.valueOf(parts[i]);
        return Records.hash(strings);
    }
}
