package com.openhire.integration.service;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.openhire.integration.security.FileIntegrationSecrets;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Durable desired state; one leased reconciliation per tenant/job/provider, including removal retries. */
@Service
public class JobPortals {
    private static final String CONNECTIONS = "job_portal_connections";
    private static final String POSTINGS = "job_portal_postings";
    private static final Set<String> PROVIDERS = Set.of("LINKEDIN", "NAUKRI");
    private final MongoTemplate mongo;
    private final JobPortalJobCatalog jobs;
    private final FileIntegrationSecrets secrets;
    private final List<JobPortalProvider> providers;
    private final AuditRecorder audit;
    private final boolean enabled;

    public JobPortals(
            MongoTemplate mongo,
            JobPortalJobCatalog jobs,
            FileIntegrationSecrets secrets,
            List<JobPortalProvider> providers,
            AuditRecorder audit,
            @Value("${openhire.integration.job-portals-enabled:false}") boolean enabled) {
        this.mongo = mongo;
        this.jobs = jobs;
        this.secrets = secrets;
        this.providers = providers;
        this.audit = audit;
        this.enabled = enabled;
    }

    public record Connection(String provider, boolean enabled, boolean verified, long revision, String status) {}

    public record Posting(String provider, boolean enabled, long revision, String status, String error) {}

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void indexes() {
        mongo.getCollection(POSTINGS).createIndex(new Document("due", 1).append("leaseUntil", 1));
        mongo.getCollection(POSTINGS).createIndex(new Document("tenant", 1).append("job", 1));
        mongo.getCollection(POSTINGS)
                .createIndex(new Document("tenant", 1).append("provider", 1).append("status", 1));
    }

    private JobPortalProvider adapter(String provider) {
        return providers.stream()
                .filter(p -> p.provider().equals(provider))
                .findFirst()
                .orElse(null);
    }

    private String key(String tenant, String resource, String provider) {
        return tenant + ":" + resource + ":" + provider;
    }

    public List<Connection> connections(String tenant) {
        return PROVIDERS.stream()
                .sorted()
                .map(provider -> {
                    var c = mongo.getCollection(CONNECTIONS)
                            .find(eq("_id", key(tenant, "connection", provider)))
                            .first();
                    boolean active = c != null && c.getBoolean("enabled", false);
                    boolean verified = active && c.getBoolean("verified", false) && adapter(provider) != null;
                    return new Connection(
                            provider,
                            active,
                            verified,
                            c == null ? 0 : revision(c),
                            adapter(provider) == null
                                    ? "PROVIDER_ACCESS_REQUIRED"
                                    : !enabled ? "DISPATCH_DISABLED" : verified ? "CONNECTED" : "NOT_CONNECTED");
                })
                .toList();
    }

    public Connection configure(
            String tenant, String actor, String provider, String secretRef, boolean active, long expected) {
        return locked(tenant, provider, () -> configureLocked(tenant, actor, provider, secretRef, active, expected));
    }

    private Connection configureLocked(
            String tenant, String actor, String provider, String secretRef, boolean active, long expected) {
        if (!PROVIDERS.contains(provider)) throw bad("Unsupported job portal");
        if (secretRef == null
                || !secretRef.matches(
                        "secret://" + java.util.regex.Pattern.quote(tenant) + "/[A-Za-z0-9][A-Za-z0-9/_-]{0,150}"))
            throw bad("Use a secret reference in this organization's namespace, never a raw token");
        var collection = mongo.getCollection(CONNECTIONS);
        String id = key(tenant, "connection", provider);
        var previous = collection.find(eq("_id", id)).first();
        if ((previous == null ? 0 : revision(previous)) != expected) throw conflict();
        // Rotating or disabling a connection must not strand any remote posting.
        if (mongo.getCollection(POSTINGS)
                        .countDocuments(and(eq("tenant", tenant), eq("provider", provider), ne("status", "REMOVED")))
                > 0)
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Turn off all postings and wait for confirmed removal before changing this connection");
        var adapter = adapter(provider);
        boolean verified = active && adapter != null && adapter.verify(secrets.read(secretRef));
        if (active && adapter != null && !verified) throw bad("Provider verification failed");
        var next = new Document("_id", id)
                .append("tenant", tenant)
                .append("provider", provider)
                .append("secretRef", secretRef)
                .append("enabled", active)
                .append("verified", verified)
                .append("revision", expected + 1);
        if (previous == null) {
            try {
                collection.insertOne(next);
            } catch (com.mongodb.MongoWriteException error) {
                if (error.getError().getCode() == 11000) throw conflict();
                throw error;
            }
        } else if (collection
                        .replaceOne(and(eq("_id", id), eq("revision", expected)), next)
                        .getModifiedCount()
                != 1) throw conflict();
        audit.record(AuditRecord.of(tenant, "JOB_PORTAL_CONFIGURED")
                .user(actor, null)
                .resource("JobPortal", provider)
                .detail("enabled", active)
                .build());
        return connections(tenant).stream()
                .filter(c -> c.provider().equals(provider))
                .findFirst()
                .orElseThrow();
    }

    public List<Posting> postings(String tenant, String job) {
        jobs.get(tenant, job); // Verify tenant ownership before returning channel state.
        var result = new ArrayList<Posting>();
        for (var d : mongo.getCollection(POSTINGS).find(and(eq("tenant", tenant), eq("job", job)))) result.add(view(d));
        return result;
    }

    public void lifecycleChanged(String tenant, String job) {
        // Ignore historical events for jobs that were never opted into external publishing.
        if (mongo.getCollection(POSTINGS).countDocuments(and(eq("tenant", tenant), eq("job", job), eq("enabled", true)))
                == 0) return;
        if (JobPortalJobCatalog.publishable(jobs.get(tenant, job))) return;
        mongo.getCollection(POSTINGS)
                .updateMany(
                        and(eq("tenant", tenant), eq("job", job), eq("enabled", true)),
                        combine(
                                set("enabled", false),
                                set("status", "QUEUED"),
                                set("due", new Date()),
                                inc("revision", 1L)));
    }

    public Posting desire(String tenant, String actor, String job, String provider, boolean on, long expected) {
        return locked(tenant, provider, () -> desireLocked(tenant, actor, job, provider, on, expected));
    }

    private Posting desireLocked(String tenant, String actor, String job, String provider, boolean on, long expected) {
        if (!PROVIDERS.contains(provider)) throw bad("Unsupported job portal");
        var snapshot = jobs.get(tenant, job);
        if (on && !JobPortalJobCatalog.publishable(snapshot))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an open, approved job version can publish");
        if (on
                && (!enabled
                        || connections(tenant).stream()
                                .noneMatch(c -> c.provider().equals(provider) && c.verified())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Portal is not active and verified for publishing");
        var collection = mongo.getCollection(POSTINGS);
        String id = key(tenant, job, provider);
        var previous = collection.find(eq("_id", id)).first();
        if ((previous == null ? 0 : revision(previous)) != expected) throw conflict();
        if (previous == null) {
            var initial = new Document("_id", id)
                    .append("tenant", tenant)
                    .append("job", job)
                    .append("provider", provider)
                    .append("enabled", on)
                    .append("revision", 1L)
                    .append("status", on ? "QUEUED" : "REMOVED")
                    .append("leaseUntil", Date.from(Instant.EPOCH))
                    .append("due", Date.from(Instant.now()));
            try {
                collection.insertOne(initial);
            } catch (com.mongodb.MongoWriteException error) {
                if (error.getError().getCode() == 11000) throw conflict();
                throw error;
            }
        } else if (collection
                        .updateOne(
                                and(eq("_id", id), eq("revision", expected)),
                                combine(
                                        set("enabled", on),
                                        set("status", "QUEUED"),
                                        unset("error"),
                                        set("due", Date.from(Instant.now())),
                                        inc("revision", 1L)))
                        .getModifiedCount()
                != 1) throw conflict();
        audit.record(AuditRecord.of(tenant, "JOB_PORTAL_REQUESTED")
                .user(actor, null)
                .resource("Job", job)
                .detail("provider", provider)
                .detail("enabled", on)
                .build());
        return view(collection.find(eq("_id", id)).first());
    }

    @Scheduled(fixedDelayString = "${openhire.integration.job-portals-poll-ms:15000}")
    public void reconcile() {
        if (!enabled) return;
        var collection = mongo.getCollection(POSTINGS);
        for (int i = 0; i < 20; i++) {
            var now = Instant.now();
            String lease = UUID.randomUUID().toString();
            var d = collection.findOneAndUpdate(
                    and(lte("due", Date.from(now)), lte("leaseUntil", Date.from(now)), ne("status", "REMOVED")),
                    combine(set("lease", lease), set("leaseUntil", Date.from(now.plusSeconds(120)))),
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (d == null) return;
            var owned = and(eq("_id", d.getString("_id")), eq("lease", lease));
            try {
                var snapshot = jobs.get(d.getString("tenant"), d.getString("job"));
                // Re-read the authoritative lifecycle even for PUBLISHED rows, repairing missed close events.
                boolean on = d.getBoolean("enabled", false) && JobPortalJobCatalog.publishable(snapshot);
                var config = mongo.getCollection(CONNECTIONS)
                        .find(eq("_id", key(d.getString("tenant"), "connection", d.getString("provider"))))
                        .first();
                var adapter = adapter(d.getString("provider"));
                if (config == null
                        || adapter == null
                        || (on && (!config.getBoolean("enabled", false) || !config.getBoolean("verified", false))))
                    throw new IllegalStateException();
                collection.updateOne(
                        and(owned, eq("revision", revision(d))), set("status", on ? "PUBLISHING" : "REMOVING"));
                var result = adapter.reconcile(
                        secrets.read(config.getString("secretRef")),
                        d.getString("_id"),
                        d.getString("remoteId"),
                        snapshot,
                        on);
                if (result == null
                        || (on
                                && result.confirmed()
                                && (result.remoteId() == null
                                        || result.remoteId().isBlank())))
                    throw new IllegalStateException("Provider confirmation must include the remote posting identity");
                // Always retain the external ID even if the user toggled OFF during a create.
                if (result.remoteId() != null && !result.remoteId().isBlank())
                    collection.updateOne(owned, set("remoteId", result.remoteId()));
                collection.updateOne(
                        and(owned, eq("revision", revision(d))),
                        combine(
                                set(
                                        "status",
                                        result.confirmed()
                                                ? (on ? "PUBLISHED" : "REMOVED")
                                                : (on ? "PUBLISHING" : "REMOVING")),
                                set("enabled", on),
                                unset("error")));
                collection.updateOne(
                        owned,
                        combine(
                                set("due", Date.from(now.plusSeconds(result.confirmed() ? 60 : 15))),
                                set("leaseUntil", Date.from(Instant.EPOCH)),
                                unset("lease")));
            } catch (RuntimeException failure) {
                // Never expose credentials, vendor response bodies, or applicant information.
                collection.updateOne(
                        and(owned, eq("revision", revision(d))),
                        combine(
                                set("status", "FAILED"),
                                set("error", "Portal synchronization failed. Check the connection and retry.")));
                collection.updateOne(
                        owned,
                        combine(
                                set("due", Date.from(now.plusSeconds(60))),
                                set("leaseUntil", Date.from(Instant.EPOCH)),
                                unset("lease")));
            }
        }
    }

    private Posting view(Document d) {
        return new Posting(
                d.getString("provider"),
                d.getBoolean("enabled", false),
                revision(d),
                d.getString("status"),
                d.getString("error"));
    }

    /** Serializes connection rotation with enqueueing; a count-then-disable race could strand a remote post. */
    private <T> T locked(String tenant, String provider, java.util.function.Supplier<T> action) {
        if (!PROVIDERS.contains(provider)) throw bad("Unsupported job portal");
        var locks = mongo.getCollection("job_portal_locks");
        String id = key(tenant, "lock", provider), token = UUID.randomUUID().toString();
        try {
            locks.insertOne(new Document("_id", id).append("until", new Date(0)));
        } catch (com.mongodb.MongoWriteException exists) {
            if (exists.getError().getCode() != 11000) throw exists;
        }
        var acquired = locks.findOneAndUpdate(
                and(eq("_id", id), lte("until", new Date())),
                combine(
                        set("token", token),
                        set("until", Date.from(Instant.now().plusSeconds(120)))));
        if (acquired == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Portal configuration is busy; retry shortly");
        try {
            return action.get();
        } finally {
            locks.deleteOne(and(eq("_id", id), eq("token", token)));
        }
    }

    private static long revision(Document d) {
        return ((Number) d.get("revision")).longValue();
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "State changed; reload before retrying");
    }
}
