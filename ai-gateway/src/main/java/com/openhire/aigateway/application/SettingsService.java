package com.openhire.aigateway.application;

import com.openhire.aigateway.application.AiExceptions.ValidationFailed;
import com.openhire.aigateway.application.port.AiPorts.ModelProvider;
import com.openhire.aigateway.application.port.AiPorts.ProviderCatalog;
import com.openhire.aigateway.application.port.AiPorts.SettingsStore;
import com.openhire.aigateway.domain.AiSettings;
import com.openhire.aigateway.domain.AiSettings.TaskRoute;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.CircuitState;
import com.openhire.aigateway.domain.AiTypes.ModelRef;
import com.openhire.aigateway.domain.AiTypes.ProviderKind;
import com.openhire.aigateway.domain.AiTypes.ProviderStatus;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and changes a tenant's model routing, budget and review switches. */
public class SettingsService {
    public record TaskIn(String provider, String model, ModelRef fallback) {}

    public record UpdateInput(
            Boolean localOnly,
            Map<String, TaskIn> tasks,
            Double monthlyBudgetInr,
            Boolean showScoreToHiringManagers,
            Boolean blindReviewDefault) {}

    public record TaskView(String provider, String model, ModelRef fallback) {}

    public record ProviderView(
            String id, ProviderKind kind, String baseUrl, boolean local, ProviderStatus status, List<String> models) {}

    public record SettingsView(
            boolean localOnly,
            Map<String, TaskView> tasks,
            double monthlyBudgetInr,
            double usedInr,
            boolean showScoreToHiringManagers,
            boolean blindReviewDefault,
            List<ProviderView> providers) {}

    private final TaskRouter router;
    private final SettingsStore store;
    private final ProviderCatalog providers;
    private final ProviderGuards guards;
    private final CostMeter meter;
    private final Clock clock;
    private final AuditRecorder audit;

    public SettingsService(
            TaskRouter router,
            SettingsStore store,
            ProviderCatalog providers,
            ProviderGuards guards,
            CostMeter meter,
            Clock clock,
            AuditRecorder audit) {
        this.router = router;
        this.store = store;
        this.providers = providers;
        this.guards = guards;
        this.meter = meter;
        this.clock = clock;
        this.audit = audit;
    }

    public SettingsView view(String tenantId) {
        var settings = router.effective(tenantId);
        var tasks = new LinkedHashMap<String, TaskView>();
        for (var task : AiTask.values()) {
            var route = settings.tasks().get(task.name());
            tasks.put(task.key(), new TaskView(route.provider(), route.model(), route.fallback()));
        }
        var views = providers.all().stream().map(this::providerView).toList();
        return new SettingsView(
                settings.localOnly(),
                tasks,
                settings.monthlyBudgetInr(),
                meter.used(tenantId).costInr(),
                settings.showScoreToHiringManagers(),
                settings.blindReviewDefault(),
                views);
    }

    private ProviderView providerView(ModelProvider provider) {
        if (guards.state(provider.id()) == CircuitState.OPEN)
            return new ProviderView(
                    provider.id(),
                    provider.kind(),
                    provider.baseUrl(),
                    provider.local(),
                    ProviderStatus.CIRCUIT_OPEN,
                    List.of());
        var health = provider.health();
        return new ProviderView(
                provider.id(),
                provider.kind(),
                provider.baseUrl(),
                provider.local(),
                health.up() ? ProviderStatus.UP : ProviderStatus.DOWN,
                health.models());
    }

    public SettingsView update(String tenantId, String actor, UpdateInput input) {
        var current = router.effective(tenantId);
        var tasks = new LinkedHashMap<>(current.tasks());
        if (input.tasks() != null)
            input.tasks().forEach((key, value) -> {
                var task = AiTask.ofKey(key)
                        .orElseThrow(() -> new ValidationFailed("Unknown task " + key, "tasks." + key));
                if (value == null || blank(value.provider()) || blank(value.model()))
                    throw new ValidationFailed(
                            "Task " + key + " needs a provider and a model", "tasks." + key + ".provider");
                var fallback = value.fallback();
                if (fallback != null && (blank(fallback.provider()) || blank(fallback.model())))
                    throw new ValidationFailed(
                            "Task " + key + " has an incomplete fallback", "tasks." + key + ".fallback");
                tasks.put(
                        task.name(),
                        new TaskRoute(value.provider().strip(), value.model().strip(), fallback));
            });
        double budget = input.monthlyBudgetInr() == null ? current.monthlyBudgetInr() : input.monthlyBudgetInr();
        if (budget < 0 || Double.isNaN(budget))
            throw new ValidationFailed("monthlyBudgetInr cannot be negative", "monthlyBudgetInr");
        var next = new AiSettings(
                tenantId,
                input.localOnly() == null ? current.localOnly() : input.localOnly(),
                tasks,
                budget,
                input.showScoreToHiringManagers() == null
                        ? current.showScoreToHiringManagers()
                        : input.showScoreToHiringManagers(),
                input.blindReviewDefault() == null ? current.blindReviewDefault() : input.blindReviewDefault(),
                clock.instant(),
                actor);
        validate(next);
        store.save(next);
        audit.record(AuditRecord.of(tenantId, "AI_SETTINGS_CHANGED")
                .user(actor, null)
                .resource("AiSettings", tenantId)
                .detail("localOnly", next.localOnly())
                .detail("monthlyBudgetInr", next.monthlyBudgetInr())
                .detail("showScoreToHiringManagers", next.showScoreToHiringManagers())
                .detail("blindReviewDefault", next.blindReviewDefault())
                .detail(
                        "tasksChanged",
                        input.tasks() == null ? 0 : input.tasks().size())
                .build());
        return view(tenantId);
    }

    /** Every referenced provider must exist, and with localOnly on every one must be local. */
    void validate(AiSettings settings) {
        for (var task : AiTask.values()) {
            var route = settings.tasks().get(task.name());
            check(settings, task, new ModelRef(route.provider(), route.model()));
            if (route.fallback() != null) check(settings, task, route.fallback());
        }
    }

    private void check(AiSettings settings, AiTask task, ModelRef ref) {
        var provider = providers
                .find(ref.provider())
                .orElseThrow(() -> new ValidationFailed(
                        "Provider " + ref.provider() + " (task " + task.key() + ") is not configured",
                        "tasks." + task.key() + ".provider"));
        if (settings.localOnly() && !provider.local())
            throw new ValidationFailed(
                    "Local-only mode forbids provider " + provider.id() + " (task " + task.key()
                            + "); switch the task to a local provider or turn local-only off",
                    "tasks." + task.key() + ".provider");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
