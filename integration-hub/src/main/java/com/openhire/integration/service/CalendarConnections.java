package com.openhire.integration.service;

import static com.openhire.integration.service.CalendarPorts.*;

import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

public class CalendarConnections {
    private final IntegrationService.Store configs;
    private final Store store;
    private final Secrets secrets;
    private final Provider provider;
    private final Clock clock;
    private final AuditRecorder audit;

    public CalendarConnections(
            IntegrationService.Store configs,
            Store store,
            Secrets secrets,
            Provider provider,
            Clock clock,
            AuditRecorder audit) {
        this.configs = configs;
        this.store = store;
        this.secrets = secrets;
        this.provider = provider;
        this.clock = clock;
        this.audit = audit;
    }

    private IntegrationConfig config(String tenant, String id) {
        var c = configs.find(tenant, "CONNECTOR", id)
                .orElseThrow(() -> new ResourceNotFoundException("Connector not found"));
        if (!c.enabled()) throw new IllegalArgumentException("Connector is disabled");
        if (!c.secretRef().startsWith("secret://" + tenant + "/"))
            throw new IllegalArgumentException("Update the connector to use this organization's secret namespace");
        return c;
    }

    public String authorize(String tenant, String actor, String id) {
        var c = config(tenant, id);
        var credentials = secrets.credentials(c.secretRef());
        String state = random(), verifier = random();
        String url = provider.authorize(c.provider(), credentials, state, challenge(verifier));
        var existing = store.load(tenant, id);
        store.pending(new Pending(
                state,
                tenant,
                actor,
                id,
                c.version(),
                existing == null ? 0 : existing.revision(),
                verifier,
                clock.instant().plusSeconds(600)));
        return url;
    }

    public void complete(String tenant, String actor, String state, String code) {
        if (state == null || state.length() > 200 || code == null || code.isBlank() || code.length() > 8192)
            throw new IllegalArgumentException("Authorization state and code required");
        var pending = store.consume(state, tenant, actor, clock.instant());
        if (pending == null)
            throw new IllegalArgumentException("Authorization expired, already used or belongs to another user");
        var c = config(tenant, pending.connector());
        if (c.version() != pending.version()) throw new IntegrationService.Conflict();
        var existing = store.load(tenant, c.id());
        long revision = existing == null ? 0 : existing.revision();
        if (revision != pending.grantRevision()) throw new IntegrationService.Conflict();
        var grant = provider.exchange(
                c.provider(), secrets.credentials(c.secretRef()), code, pending.verifier(), clock.instant());
        long nextRevision = revision + 1;
        if (!store.save(new SavedGrant(tenant, c.id(), c.version(), nextRevision, grant), revision))
            throw new IntegrationService.Conflict();
        audit.record(AuditRecord.of(tenant, "CONNECTOR_AUTHORIZED")
                .user(actor, null)
                .resource("CONNECTOR", c.id())
                .build());
    }

    public String status(String tenant, String id) {
        var c = config(tenant, id);
        var saved = store.load(tenant, id);
        if (saved == null || saved.grant() == null || saved.configVersion() != c.version())
            return "PENDING_VERIFICATION";
        return saved.grant().expiresAt().isAfter(clock.instant())
                        || saved.grant().refreshToken() != null
                ? "AUTHORIZED"
                : "REAUTHORIZATION_REQUIRED";
    }

    public Map<String, Object> list(String tenant, String id) {
        var c = config(tenant, id);
        return provider.list(c.provider(), grant(tenant, id, c).accessToken());
    }

    public Map<String, Object> write(
            String tenant,
            String id,
            String stableId,
            String remoteId,
            com.openhire.integration.domain.CalendarEvent event,
            boolean cancel) {
        var c = config(tenant, id);
        return provider.write(c.provider(), grant(tenant, id, c).accessToken(), stableId, remoteId, event, cancel);
    }

    private Grant grant(String tenant, String id, IntegrationConfig c) {
        var saved = store.load(tenant, id);
        if (saved == null || saved.grant() == null || saved.configVersion() != c.version())
            throw new IllegalArgumentException("Authorize this connector first");
        var grant = saved.grant();
        if (!grant.expiresAt().isAfter(clock.instant().plusSeconds(60))) {
            grant = provider.refresh(c.provider(), secrets.credentials(c.secretRef()), grant, clock.instant());
            if (!store.save(new SavedGrant(tenant, id, c.version(), saved.revision() + 1, grant), saved.revision()))
                throw new IntegrationService.Conflict();
        }
        return grant;
    }

    public void disconnect(String tenant, String actor, String id) {
        configs.find(tenant, "CONNECTOR", id).orElseThrow(() -> new ResourceNotFoundException("Connector not found"));
        store.disconnect(tenant, id);
        audit.record(AuditRecord.of(tenant, "CONNECTOR_DISCONNECTED")
                .user(actor, null)
                .resource("CONNECTOR", id)
                .build());
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
