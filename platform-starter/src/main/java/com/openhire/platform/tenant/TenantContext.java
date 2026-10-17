package com.openhire.platform.tenant;

import java.util.Optional;

public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static String requiredTenantId() {
        return Optional.ofNullable(CURRENT.get())
                .orElseThrow(() -> new IllegalStateException("Tenant context is not available"));
    }

    static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    static void clear() {
        CURRENT.remove();
    }
}
