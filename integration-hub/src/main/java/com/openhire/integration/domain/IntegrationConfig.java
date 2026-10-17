package com.openhire.integration.domain;

import java.net.URI;
import java.util.List;
import java.util.Set;

public record IntegrationConfig(
        String id,
        String tenantId,
        String kind,
        String name,
        String provider,
        String endpoint,
        String secretRef,
        List<String> eventTypes,
        boolean enabled,
        long version) {
    public static final Set<String> PROVIDERS = Set.of("GOOGLE_CALENDAR", "MICROSOFT_365");
    public static final Set<String> EVENTS =
            Set.of("application.created", "interview.scheduled", "offer.accepted", "employee.activated");

    public IntegrationConfig {
        if (id == null || tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("Identity and tenant required");
        if (name == null || name.isBlank() || name.length() > 100)
            throw new IllegalArgumentException("Name must have 1–100 characters");
        name = name.trim();
        if (secretRef == null || !secretRef.matches("secret://[A-Za-z0-9][A-Za-z0-9/_-]{0,199}"))
            throw new IllegalArgumentException("Use a secret:// reference, never an OAuth token or signing secret");
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        if ("CONNECTOR".equals(kind)) {
            if (!PROVIDERS.contains(provider == null ? "" : provider))
                throw new IllegalArgumentException("Unsupported provider");
            endpoint = null;
            eventTypes = List.of();
        } else if ("WEBHOOK".equals(kind)) {
            validateEndpoint(endpoint);
            if (eventTypes.isEmpty() || eventTypes.size() > EVENTS.size() || !EVENTS.containsAll(eventTypes))
                throw new IllegalArgumentException("Select supported webhook events");
            eventTypes = eventTypes.stream().distinct().sorted().toList();
            provider = null;
        } else throw new IllegalArgumentException("Unknown configuration kind");
        if (version < 1) throw new IllegalArgumentException("Version must be positive");
    }
    /** Registration validation only. Dispatch must additionally validate resolved IPs and forbid redirects. */
    static void validateEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid HTTPS endpoint");
        }
        var host = uri.getHost() == null ? null : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !host.matches("(?i)[a-z0-9-]+(?:\\.[a-z0-9-]+)+")
                || host.matches("[0-9.]+")
                || host.endsWith(".local")
                || host.endsWith(".localhost")
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || value.length() > 2048)
            throw new IllegalArgumentException("Use a public HTTPS hostname without credentials, query or fragment");
    }

    public String status() {
        return !enabled ? "DISABLED" : "CONNECTOR".equals(kind) ? "PENDING_VERIFICATION" : "DELIVERY_NOT_ENABLED";
    }
}
