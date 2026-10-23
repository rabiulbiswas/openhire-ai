package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import com.openhire.aigateway.domain.PromptVersion;
import com.openhire.aigateway.domain.SeedPrompts;
import com.openhire.aigateway.exception.AiExceptions.AiUnavailable;
import com.openhire.aigateway.service.port.AiPorts.PromptStore;
import com.openhire.support.Records;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Versioned prompts per tenant. Seeds are inserted on first use and never overwrite edits. */
public class PromptRegistry {
    private final PromptStore store;
    private final Clock clock;
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    public PromptRegistry(PromptStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void ensureSeeded(String tenantId) {
        if (seeded.contains(tenantId)) return;
        SeedPrompts.forTenant(tenantId, clock.instant()).forEach(store::insertIfAbsent);
        seeded.add(tenantId);
    }

    public List<PromptVersion> list(String tenantId) {
        ensureSeeded(tenantId);
        return store.list(tenantId);
    }

    public PromptVersion get(String tenantId, String id) {
        ensureSeeded(tenantId);
        return store.find(tenantId, id)
                .orElseThrow(() ->
                        new com.openhire.platform.web.ResourceNotFoundException("Prompt " + id + " was not found"));
    }

    public List<PromptVersion> versions(String tenantId, String key) {
        ensureSeeded(tenantId);
        return store.versions(tenantId, key);
    }

    public PromptVersion save(PromptVersion prompt) {
        return store.save(prompt);
    }

    /**
     * Picks the ACTIVE version for a request. The newest ACTIVE version receives its trafficPct of requests, by a
     * stable hash of the request key; the rest go to older ACTIVE versions. SHADOW versions are never returned.
     */
    public PromptVersion select(String tenantId, String key, String requestKey) {
        ensureSeeded(tenantId);
        var active = store.versions(tenantId, key).stream()
                .filter(p -> p.status() == PromptStatus.ACTIVE)
                .sorted(Comparator.comparingInt(PromptVersion::version).reversed())
                .toList();
        if (active.isEmpty()) throw new AiUnavailable("No active prompt for " + key);
        int bucket = bucket(tenantId, key, requestKey);
        int floor = 0;
        for (var prompt : active) {
            floor += prompt.trafficPct();
            if (bucket < floor) return prompt;
        }
        return active.get(active.size() - 1);
    }

    /** A stable 0..99 bucket for a request. */
    static int bucket(String tenantId, String key, String requestKey) {
        return (int) (Long.parseLong(Records.hash(tenantId, key, requestKey).substring(0, 8), 16) % 100);
    }
}
