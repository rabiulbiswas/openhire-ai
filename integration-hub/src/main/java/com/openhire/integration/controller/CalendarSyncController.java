package com.openhire.integration.controller;

import com.openhire.integration.service.IntegrationService;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import com.openhire.support.Records;
import java.time.Clock;
import java.util.Date;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/connectors/interview-sync")
class CalendarSyncController {
    private final MongoTemplate mongo;
    private final IntegrationService.Store configs;
    private final Access access;
    private final Clock clock;
    private final AuditRecorder audit;

    CalendarSyncController(
            MongoTemplate mongo, IntegrationService.Store configs, Access access, Clock clock, AuditRecorder audit) {
        this.mongo = mongo;
        this.configs = configs;
        this.access = access;
        this.clock = clock;
        this.audit = audit;
    }

    private String tenant(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return access.tenant(auth);
    }

    record Input(String connectorId, boolean enabled) {}

    @GetMapping
    Document get(Authentication auth) {
        var result = mongo.getCollection("calendar_sync_bindings")
                .find(new Document("_id", tenant(auth)))
                .first();
        return result == null ? new Document("enabled", false) : result;
    }

    @PutMapping
    Document save(Authentication auth, @RequestBody Input input) {
        String tenant = tenant(auth);
        var c = configs.find(tenant, "CONNECTOR", input.connectorId()).orElseThrow(Records::missing);
        if (input.enabled() && !c.enabled()) throw new IllegalArgumentException("Enable the connector first");
        var collection = mongo.getCollection("calendar_sync_bindings");
        var prior = collection.find(new Document("_id", tenant)).first();
        if (prior != null && !input.connectorId().equals(prior.getString("connector")))
            throw Records.conflict(
                    "Changing the sync account requires reconciliation of existing calendar events first");
        var next = new Document("_id", tenant)
                .append("connector", input.connectorId())
                .append("enabled", input.enabled())
                .append(
                        "enabledAt",
                        prior != null && prior.getBoolean("enabled", false)
                                ? prior.getDate("enabledAt")
                                : Date.from(clock.instant()));
        if (prior == null) {
            try {
                collection.insertOne(next);
            } catch (com.mongodb.MongoWriteException conflict) {
                if (conflict.getError().getCode() == 11000)
                    throw Records.conflict("Sync configuration changed; reload");
                throw conflict;
            }
        } else collection.replaceOne(new Document("_id", tenant).append("connector", input.connectorId()), next);
        audit.record(AuditRecord.of(tenant, "INTERVIEW_CALENDAR_SYNC_CONFIGURED")
                .user(access.actor(auth), null)
                .resource("CONNECTOR", input.connectorId())
                .detail("enabled", input.enabled())
                .build());
        return next;
    }
}
