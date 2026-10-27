package com.openhire.integration.service;

import java.util.Map;

/** Provider port. Implementations require an approved vendor contract; no simulated production adapter. */
public interface JobPortalProvider {
    String provider();

    /** Verify credentials, posting/removal permissions and selective per-job publishing entitlement. */
    boolean verify(String credentials);

    /**
     * Reconcile by stable posting key (including lookup after a timed-out create). Must be idempotent.
     * confirmed means the provider has confirmed the actual state, not merely accepted an async task.
     * Tokens and provider response bodies must never be returned in error messages.
     */
    Result reconcile(
            String credentials, String stableKey, String remoteId, Map<String, Object> approvedJob, boolean enabled);

    record Result(boolean confirmed, String remoteId) {}
}
