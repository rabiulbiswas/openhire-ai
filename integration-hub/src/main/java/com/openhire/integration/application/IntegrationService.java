package com.openhire.integration.application;

import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;

public class IntegrationService {
    public interface Store {
        List<IntegrationConfig> list(String tenant, String kind);

        List<IntegrationConfig> page(String tenant, String kind, String after, int limit);

        java.util.Optional<IntegrationConfig> find(String tenant, String kind, String id);

        boolean save(IntegrationConfig config, long expectedVersion);
    }

    public static class Conflict extends RuntimeException {
        public Conflict() {
            super("Configuration changed; reload before saving");
        }
    }

    private final Store store;
    private final AuditRecorder audit;

    public IntegrationService(Store store, AuditRecorder audit) {
        this.store = store;
        this.audit = audit;
    }

    public List<IntegrationConfig> list(String tenant, String kind) {
        return store.list(tenant, kind);
    }

    public List<IntegrationConfig> page(String tenant, String kind, String after, int limit) {
        if (limit < 1 || limit > 200 || (after != null && after.length() > 200))
            throw new IllegalArgumentException("Page size must be 1–200 and cursor at most 200 characters");
        return store.page(tenant, kind, after, limit);
    }

    public IntegrationConfig save(
            String tenant,
            String actor,
            String kind,
            String id,
            Long expectedVersion,
            String name,
            String provider,
            String endpoint,
            String secretRef,
            List<String> events,
            boolean enabled) {
        if (secretRef == null || !secretRef.startsWith("secret://" + tenant + "/"))
            throw new IllegalArgumentException("Secret reference must be inside this organization's namespace");
        boolean create = id == null;
        if (!create && (expectedVersion == null || expectedVersion < 1))
            throw new IllegalArgumentException("Expected version required");
        if (!create && store.find(tenant, kind, id).isEmpty())
            throw new ResourceNotFoundException("Configuration not found");
        long expected = create ? 0 : expectedVersion;
        var config = new IntegrationConfig(
                create ? UUID.randomUUID().toString() : id,
                tenant,
                kind,
                name,
                provider,
                endpoint,
                secretRef,
                events,
                enabled,
                expected + 1);
        if (!store.save(config, expected)) throw new Conflict();
        audit.record(AuditRecord.of(tenant, create ? "INTEGRATION_CONFIG_CREATED" : "INTEGRATION_CONFIG_UPDATED")
                .user(actor, null)
                .resource(kind, config.id())
                .detail("version", config.version())
                .detail("enabled", enabled)
                .build());
        return config;
    }
}
