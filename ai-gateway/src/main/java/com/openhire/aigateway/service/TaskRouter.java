package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiSettings;
import com.openhire.aigateway.domain.AiSettings.TaskRoute;
import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.ModelRef;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import com.openhire.aigateway.service.port.AiPorts.ModelProvider;
import com.openhire.aigateway.service.port.AiPorts.ProviderCatalog;
import com.openhire.aigateway.service.port.AiPorts.SettingsStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Maps a task to the provider and model that should serve it for a tenant, with its fallback chain. */
public class TaskRouter {
    public record Route(ModelProvider provider, String model) {}

    private final SettingsStore settings;
    private final ProviderCatalog providers;
    private final AiDefaults defaults;

    public TaskRouter(SettingsStore settings, ProviderCatalog providers, AiDefaults defaults) {
        this.settings = settings;
        this.providers = providers;
        this.defaults = defaults;
    }

    /** The tenant's settings with every task filled from the defaults. */
    public AiSettings effective(String tenantId) {
        var stored = settings.find(tenantId);
        var tasks = new LinkedHashMap<String, TaskRoute>();
        for (var task : AiTask.values()) {
            var fallback = new TaskRoute(
                    defaults.providerId(),
                    task == AiTask.EMBEDDINGS ? defaults.embeddingModel() : defaults.chatModel(),
                    null);
            var configured = stored.map(AiSettings::tasks).map(t -> t.get(task.name()));
            tasks.put(task.name(), configured.orElse(fallback));
        }
        return stored.map(s -> new AiSettings(
                        tenantId,
                        s.localOnly(),
                        tasks,
                        s.monthlyBudgetInr(),
                        s.showScoreToHiringManagers(),
                        s.blindReviewDefault(),
                        s.updatedAt(),
                        s.updatedBy()))
                .orElseGet(() -> new AiSettings(
                        tenantId, defaults.localOnly(), tasks, defaults.monthlyBudgetInr(), true, false, null, null));
    }

    /** Providers permitted for the task, primary first. With localOnly on, non-local providers are skipped. */
    public List<Route> routes(String tenantId, AiTask task) {
        var current = effective(tenantId);
        var route = current.tasks().get(task.name());
        var refs = new ArrayList<ModelRef>();
        refs.add(new ModelRef(route.provider(), route.model()));
        if (route.fallback() != null) refs.add(route.fallback());
        var out = new ArrayList<Route>();
        for (var ref : refs)
            providers
                    .find(ref.provider())
                    .filter(p -> !current.localOnly() || p.local())
                    .ifPresent(p -> out.add(new Route(p, ref.model())));
        if (out.isEmpty()) throw new AiUnavailable("No permitted model is configured for " + task.key());
        return out;
    }
}
