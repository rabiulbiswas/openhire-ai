package com.openhire.integration.controller;

import com.openhire.integration.domain.CalendarEvent;
import com.openhire.integration.repository.mongo.CalendarWorkQueue;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/connectors/{id}/calendar/work")
class CalendarWorkController {
    private final CalendarWorkQueue queue;
    private final Access access;
    private final AuditRecorder audit;

    CalendarWorkController(CalendarWorkQueue queue, Access access, AuditRecorder audit) {
        this.queue = queue;
        this.access = access;
        this.audit = audit;
    }

    private String tenant(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return access.tenant(auth);
    }

    record Input(Long version, CalendarEvent event, boolean cancel) {}

    @PutMapping("/{resource}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Document save(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String resource,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Input input) {
        String tenant = tenant(auth);
        var result = queue.enqueue(tenant, id, resource, key, input.version(), input.event(), input.cancel(), null);
        audit.record(AuditRecord.of(tenant, "CALENDAR_CHANGE_QUEUED")
                .user(access.actor(auth), null)
                .resource("CALENDAR_EVENT", resource)
                .build());
        return result;
    }

    @GetMapping("/{resource}")
    Document status(Authentication auth, @PathVariable String id, @PathVariable String resource) {
        return queue.get(tenant(auth), id, resource);
    }
}
