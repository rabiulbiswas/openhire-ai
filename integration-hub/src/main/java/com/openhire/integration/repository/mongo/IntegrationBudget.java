package com.openhire.integration.repository.mongo;

import static com.mongodb.client.model.Filters.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import com.openhire.support.Records;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Atomic, cross-instance tenant budgets. Retry identities consume a slot once per UTC window. */
@Component
public class IntegrationBudget {
    private final MongoTemplate mongo;
    private final Clock clock;
    private final int requests, calendars, webhooks, configs;

    public IntegrationBudget(
            MongoTemplate mongo,
            Clock clock,
            @Value("${openhire.integration.quotas.requests-per-minute:300}") int requests,
            @Value("${openhire.integration.quotas.calendar-changes-per-day:10000}") int calendars,
            @Value("${openhire.integration.quotas.webhook-deliveries-per-day:50000}") int webhooks,
            @Value("${openhire.integration.quotas.configurations-per-day:1000}") int configs) {
        this.mongo = mongo;
        this.clock = clock;
        this.requests = valid(requests);
        this.calendars = valid(calendars);
        this.webhooks = valid(webhooks);
        this.configs = valid(configs);
    }

    private static int valid(int limit) {
        if (limit < 1 || limit > 50000) throw new IllegalArgumentException("Integration quota must be 1–50000");
        return limit;
    }

    @EventListener(ApplicationReadyEvent.class)
    void indexes() {
        mongo.getCollection("integration_budgets")
                .createIndex(new Document("expiresAt", 1), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    public void request(String tenant) {
        reserve(tenant, "requests", UUID.randomUUID().toString(), requests, 60);
    }

    public void calendar(String tenant, String key) {
        reserve(tenant, "calendar", key, calendars, 86400);
    }

    public void webhook(String tenant, String key) {
        reserve(tenant, "webhook", key, webhooks, 86400);
    }

    public void configuration(String tenant, String key) {
        reserve(tenant, "configuration", key, configs, 86400);
    }

    void reserve(String tenant, String kind, String key, int limit, int seconds) {
        if (tenant == null || tenant.isBlank() || key == null)
            throw new IllegalArgumentException("Quota identity required");
        long window = clock.instant().getEpochSecond() / seconds;
        String id = Records.hash(tenant, kind, Long.toString(window));
        String receipt = Records.hash(key);
        var collection = mongo.getCollection("integration_budgets");
        try {
            collection.updateOne(
                    eq("_id", id),
                    new Document(
                            "$setOnInsert",
                            new Document("tenant", tenant)
                                    .append("kind", kind)
                                    .append("used", 0)
                                    .append("receipts", List.of())
                                    .append("expiresAt", new Date((window + 1) * seconds * 1000 + 172800000))),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException duplicate) {
            if (duplicate.getError().getCode() != 11000) throw duplicate;
        }
        if (collection
                        .updateOne(
                                and(eq("_id", id), lt("used", limit), ne("receipts", receipt)),
                                new Document("$inc", new Document("used", 1))
                                        .append("$addToSet", new Document("receipts", receipt)))
                        .getModifiedCount()
                == 1) return;
        if (collection
                        .find(and(eq("_id", id), eq("receipts", receipt)))
                        .projection(new Document("_id", 1))
                        .first()
                != null) return;
        throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Organization " + kind + " budget exhausted; retry in the next UTC window");
    }

    public Map<String, Integer> limits() {
        return Map.of(
                "requestsPerMinute",
                requests,
                "calendarChangesPerDay",
                calendars,
                "webhookDeliveriesPerDay",
                webhooks,
                "configurationsPerDay",
                configs);
    }
}
