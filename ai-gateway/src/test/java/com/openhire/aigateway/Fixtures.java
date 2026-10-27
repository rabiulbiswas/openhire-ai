package com.openhire.aigateway;

import com.openhire.aigateway.domain.AiSettings;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.ChatRequest;
import com.openhire.aigateway.domain.AiTypes.ChatResult;
import com.openhire.aigateway.domain.AiTypes.EmbedResult;
import com.openhire.aigateway.domain.AiTypes.Health;
import com.openhire.aigateway.domain.AiTypes.ProviderKind;
import com.openhire.aigateway.domain.AiTypes.RunStatus;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.domain.Golden;
import com.openhire.aigateway.domain.PromptVersion;
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
import com.openhire.aigateway.service.port.AiPorts.RequestRecord;
import com.openhire.aigateway.service.port.AiPorts.RequestStat;
import com.openhire.aigateway.service.port.AiPorts.ResultCache;
import com.openhire.aigateway.service.port.AiPorts.SettingsStore;
import com.openhire.aigateway.service.port.AiPorts.TaskUsage;
import com.openhire.aigateway.service.port.AiPorts.Usage;
import com.openhire.aigateway.service.port.AiPorts.UsageStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** In-memory ports and a fake model provider; a Rig wires the real services around them. */
public final class Fixtures {
    public static final String TENANT = "t1";

    private Fixtures() {}

    public static Map<String, Object> jdSections(String text) {
        return Map.of(
                "roleDescription",
                text,
                "summary",
                text,
                "responsibilities",
                List.of("Build reliable services", "Collaborate with the team", "Review and test changes"),
                "technicalSkills",
                List.of("Java", "Testing", "Problem solving"));
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static final class FakeProvider implements ModelProvider {
        public final String id;
        public final boolean local;
        public final List<ChatRequest> calls = new CopyOnWriteArrayList<>();
        public final List<Duration> taskTimeouts = new CopyOnWriteArrayList<>();
        public final List<List<String>> embedCalls = new CopyOnWriteArrayList<>();
        public Function<ChatRequest, String> reply = r -> "{}";
        public RuntimeException failure;
        public int promptTokens = 100;
        public int completionTokens = 50;
        public boolean up = true;

        public FakeProvider(String id, boolean local) {
            this.id = id;
            this.local = local;
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
            return local ? "http://localhost:11434" : "https://hosted.example";
        }

        @Override
        public boolean local() {
            return local;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            calls.add(request);
            if (failure != null) throw failure;
            return new ChatResult(reply.apply(request), promptTokens, completionTokens);
        }

        @Override
        public ChatResult chat(ChatRequest request, Duration timeout) {
            taskTimeouts.add(timeout);
            return chat(request);
        }

        @Override
        public EmbedResult embed(String model, List<String> inputs) {
            embedCalls.add(inputs);
            if (failure != null) throw failure;
            var vectors = new ArrayList<float[]>();
            for (var text : inputs) vectors.add(new float[] {text.length(), text.hashCode() % 100, 1f});
            return new EmbedResult(vectors, inputs.size() * 3);
        }

        @Override
        public Health health() {
            return new Health(up, up ? List.of("qwen2.5:3b-instruct") : List.of());
        }
    }

    public static final class MemoryPrompts implements PromptStore {
        public final Map<String, PromptVersion> rows = new LinkedHashMap<>();

        @Override
        public void insertIfAbsent(PromptVersion prompt) {
            rows.putIfAbsent(prompt.docId(), prompt);
        }

        @Override
        public List<PromptVersion> list(String tenantId) {
            return rows.values().stream()
                    .filter(p -> p.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public List<PromptVersion> versions(String tenantId, String key) {
            return list(tenantId).stream().filter(p -> p.key().equals(key)).toList();
        }

        @Override
        public Optional<PromptVersion> find(String tenantId, String id) {
            return Optional.ofNullable(rows.get(tenantId + ":" + id));
        }

        @Override
        public PromptVersion save(PromptVersion prompt) {
            rows.put(prompt.docId(), prompt);
            return prompt;
        }
    }

    public static final class MemorySettings implements SettingsStore {
        public final Map<String, AiSettings> rows = new HashMap<>();

        @Override
        public Optional<AiSettings> find(String tenantId) {
            return Optional.ofNullable(rows.get(tenantId));
        }

        @Override
        public AiSettings save(AiSettings settings) {
            rows.put(settings.tenantId(), settings);
            return settings;
        }
    }

    public static final class MemoryUsage implements UsageStore {
        private final Map<String, double[]> totals = new HashMap<>();

        @Override
        public Usage add(
                String tenantId,
                String month,
                AiTask task,
                int promptTokens,
                int completionTokens,
                double costInr,
                long computeMs) {
            var t = totals.computeIfAbsent(tenantId + ":" + month, k -> new double[4]);
            t[0] += costInr;
            t[1] += promptTokens;
            t[2] += completionTokens;
            t[3] += 1;
            return get(tenantId, month);
        }

        @Override
        public Usage get(String tenantId, String month) {
            var t = totals.get(tenantId + ":" + month);
            if (t == null) return Usage.none();
            return new Usage(t[0], (long) t[1], (long) t[2], (long) t[3], 0, Map.of("x", new TaskUsage(t[0], 0, 0, 0)));
        }

        public void preload(String tenantId, String month, double costInr) {
            totals.put(tenantId + ":" + month, new double[] {costInr, 0, 0, 0});
        }
    }

    public static final class MemoryRequests implements RequestLog {
        public final List<RequestRecord> rows = new CopyOnWriteArrayList<>();

        @Override
        public void save(RequestRecord record) {
            rows.add(record);
        }

        @Override
        public List<RequestStat> since(String tenantId, Instant from) {
            return rows.stream()
                    .filter(r -> r.tenantId().equals(tenantId) && "LIVE".equals(r.purpose()))
                    .filter(r -> !r.provenance().createdAt().isBefore(from))
                    .map(r -> new RequestStat(
                            r.provenance().task(),
                            r.provenance().provider(),
                            r.provenance().model(),
                            r.provenance().latencyMs(),
                            r.outcome(),
                            r.provenance().cached(),
                            r.provenance().costInr(),
                            r.provenance().createdAt()))
                    .toList();
        }
    }

    public static final class MemoryCache implements ResultCache {
        public final Map<String, String> rows = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            rows.put(key, value);
        }
    }

    public static final class MemoryEvals implements EvalStore {
        public final Map<String, EvalRun> rows = new LinkedHashMap<>();

        @Override
        public EvalRun save(EvalRun run) {
            rows.put(run.docId(), run);
            return run;
        }

        @Override
        public Optional<EvalRun> find(String tenantId, String id) {
            return Optional.ofNullable(rows.get(tenantId + ":" + id));
        }

        @Override
        public Optional<EvalRun> running(String tenantId, Instant notBefore) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.status() == RunStatus.RUNNING)
                    .findFirst();
        }
    }

    /** Everything wired around in-memory ports. */
    public static final class Rig {
        public final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T05:00:00Z"));
        public final FakeProvider local = new FakeProvider("ollama", true);
        public final FakeProvider hosted = new FakeProvider("openai", false);
        public final MemoryPrompts prompts = new MemoryPrompts();
        public final MemorySettings settingsStore = new MemorySettings();
        public final MemoryUsage usage = new MemoryUsage();
        public final MemoryRequests requests = new MemoryRequests();
        public final MemoryCache cache = new MemoryCache();
        public final MemoryEvals evals = new MemoryEvals();
        public final AiDefaults defaults = new AiDefaults(
                "ollama", "qwen2.5:3b-instruct", "nomic-embed-text", true, 1000, Duration.ofHours(72), 8192);
        public final ProviderGuards guards =
                new ProviderGuards(clock, 2, Duration.ofSeconds(30), 2, Duration.ofMillis(300));
        public final CostMeter meter =
                new CostMeter(Map.of("gpt-test", new CostMeter.Price(1_000_000, 2_000_000)), usage, clock);
        public final TaskRouter router;
        public final PromptRegistry registry = new PromptRegistry(prompts, clock);
        public final ModelGateway gateway;
        public final AiTaskService tasks;
        public final AuditProbe audit;
        public final SettingsService settings;
        public final PromptAdminService admin;
        public final OpsService ops;
        public final EvaluationService evaluation;
        public Golden.Dataset dataset = new Golden.Dataset("test", List.of());

        public Rig() {
            this(AuditProbe.working());
        }

        public Rig(AuditProbe audit) {
            this.audit = audit;
            ProviderCatalog catalog = new ProviderCatalog() {
                public List<ModelProvider> all() {
                    return List.of(local, hosted);
                }

                public Optional<ModelProvider> find(String id) {
                    return all().stream().filter(p -> p.id().equals(id)).findFirst();
                }
            };
            router = new TaskRouter(settingsStore, catalog, defaults);
            gateway = new ModelGateway(
                    router, registry, guards, cache, meter, new ProvenanceWriter(requests), defaults, clock);
            tasks = new AiTaskService(gateway, clock);
            settings = new SettingsService(router, settingsStore, catalog, guards, meter, clock, audit.recorder());
            admin = new PromptAdminService(registry, evals, router, clock, audit.recorder());
            ops = new OpsService(requests, catalog, guards, clock);
            GoldenDatasets datasets = () -> dataset;
            evaluation = new EvaluationService(
                    registry,
                    tasks,
                    router,
                    evals,
                    datasets,
                    EvalGates.defaults(),
                    Runnable::run,
                    clock,
                    audit.recorder());
        }
    }
}
