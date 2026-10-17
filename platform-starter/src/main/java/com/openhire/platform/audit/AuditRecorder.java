package com.openhire.platform.audit;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The one way services record consequential actions. A record is first written to an outbox in the service's own
 * database, then relayed to {@code audit.events.v1} by a background thread, so it survives a Kafka outage and never
 * fails the user's action. Delivery is at least once; the compliance store deduplicates on the event id.
 */
@Component
public class AuditRecorder implements InitializingBean, DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRecorder.class);

    /** Where records wait until they are relayed. */
    public interface Outbox {
        void add(String eventId, String tenantId, String json);

        List<Pending> pending(int limit);

        void remove(String eventId);

        record Pending(String eventId, String tenantId, String json) {}
    }

    /** How a relayed record leaves the service. */
    public interface Transport {
        void send(String key, String json) throws Exception;
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Outbox outbox;
    private final Transport transport;
    private final String service;
    private final boolean enabled;
    private ScheduledExecutorService relay;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditRecorder(
            ObjectProvider<MongoTemplate> mongo,
            ObjectProvider<KafkaTemplate<String, String>> kafka,
            @Value("${spring.application.name:unknown-service}") String service,
            @Value("${openhire.audit.topic:audit.events.v1}") String topic,
            @Value("${openhire.audit.enabled:true}") boolean enabled) {
        this(
                mongo.getIfAvailable() == null ? null : new MongoOutbox(mongo.getObject()),
                kafka.getIfAvailable() == null
                        ? null
                        : (key, body) ->
                                kafka.getObject().send(topic, key, body).get(12, TimeUnit.SECONDS),
                service,
                enabled);
    }

    /** For tests and custom wiring. */
    public AuditRecorder(Outbox outbox, Transport transport, String service, boolean enabled) {
        this.outbox = outbox;
        this.transport = transport;
        this.service = service;
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (!enabled || outbox == null || transport == null) {
            LOG.warn("Audit recording is not active in {} (store or transport missing, or disabled)", service);
            return;
        }
        relay = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "audit-relay");
            thread.setDaemon(true);
            return thread;
        });
        relay.scheduleWithFixedDelay(this::drainQuietly, 2, 1, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() {
        if (relay != null) relay.shutdown();
    }

    /** Records an action. Never throws: a failure to audit is logged loudly but does not undo the action. */
    public void record(AuditRecord record) {
        if (!enabled || outbox == null) return;
        try {
            var eventId = UUID.randomUUID().toString();
            outbox.add(eventId, record.tenantId(), json.writeValueAsString(envelope(eventId, record)));
        } catch (RuntimeException e) {
            LOG.error(
                    "AUDIT RECORD LOST in {}: {} on {} {} ({})",
                    service,
                    record.action(),
                    record.resourceType(),
                    record.resourceId(),
                    e.toString());
        }
    }

    /** Relays what is waiting; returns how many records left the outbox. */
    public int drain() throws Exception {
        if (outbox == null || transport == null) return 0;
        int sent = 0;
        for (var item : outbox.pending(100)) {
            transport.send(item.tenantId(), item.json());
            outbox.remove(item.eventId());
            sent++;
        }
        return sent;
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (Exception e) {
            LOG.warn("Audit relay will retry: {}", e.toString());
        }
    }

    Map<String, Object> envelope(String eventId, AuditRecord record) {
        var actor = new LinkedHashMap<String, Object>();
        actor.put("type", record.actorType().name());
        actor.put("id", record.actorId());
        if (record.actorName() != null) actor.put("name", record.actorName());
        var resource = Map.of("type", record.resourceType(), "id", record.resourceId());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", record.action());
        payload.put("actor", actor);
        payload.put("resource", resource);
        if (record.candidateId() != null) payload.put("candidateId", record.candidateId());
        if (record.applicationId() != null) payload.put("applicationId", record.applicationId());
        payload.put("details", record.details());
        payload.put("at", record.at().toString());
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", eventId);
        envelope.put("producer", service);
        envelope.put("eventType", "audit.event.recorded.v1");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", record.at().toString());
        envelope.put("tenantId", record.tenantId());
        envelope.put("aggregateType", "Audit");
        envelope.put("aggregateId", eventId);
        envelope.put("correlationId", record.applicationId() != null ? record.applicationId() : record.resourceId());
        envelope.put("payload", payload);
        return envelope;
    }

    /** Outbox in the service's own MongoDB, in the collection {@code audit_outbox}. */
    static final class MongoOutbox implements Outbox {
        private static final String COLLECTION = "audit_outbox";
        private final MongoTemplate mongo;

        MongoOutbox(MongoTemplate mongo) {
            this.mongo = mongo;
        }

        @Override
        public void add(String eventId, String tenantId, String json) {
            mongo.getCollection(COLLECTION)
                    .insertOne(new Document("_id", eventId)
                            .append("tenantId", tenantId)
                            .append("json", json)
                            .append("createdAt", new java.util.Date()));
        }

        @Override
        public List<Pending> pending(int limit) {
            var out = new ArrayList<Pending>();
            for (var doc : mongo.getCollection(COLLECTION)
                    .find()
                    .sort(Sorts.ascending("createdAt"))
                    .limit(limit))
                out.add(new Pending(doc.getString("_id"), doc.getString("tenantId"), doc.getString("json")));
            return out;
        }

        @Override
        public void remove(String eventId) {
            mongo.getCollection(COLLECTION).deleteOne(Filters.eq("_id", eventId));
        }
    }
}
