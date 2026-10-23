package com.openhire.integration.controller;

import com.openhire.integration.repository.mongo.WebhookDeliveryQueue;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.support.Access;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookDeliveryController {
    private final WebhookDeliveryQueue queue;
    private final Access access;
    private final AuditRecorder audit;

    WebhookDeliveryController(WebhookDeliveryQueue queue, Access access, AuditRecorder audit) {
        this.queue = queue;
        this.access = access;
        this.audit = audit;
    }

    private String tenant(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return access.tenant(auth);
    }

    @GetMapping("/delivery-status")
    Map<String, Boolean> status(Authentication auth) {
        tenant(auth);
        return Map.of("enabled", queue.enabled());
    }

    @GetMapping("/{id}/deliveries")
    List<Document> history(Authentication auth, @PathVariable String id) {
        return queue.history(tenant(auth), id);
    }

    @PostMapping("/{id}/deliveries/{delivery}/replay")
    Map<String, String> replay(Authentication auth, @PathVariable String id, @PathVariable String delivery) {
        String tenant = tenant(auth);
        if (!queue.replay(tenant, id, delivery))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only a failed delivery in this organization can be replayed");
        audit.record(AuditRecord.of(tenant, "WEBHOOK_REPLAY_REQUESTED")
                .user(access.actor(auth), null)
                .resource("WEBHOOK_DELIVERY", delivery)
                .build());
        return Map.of("status", "PENDING");
    }
}
