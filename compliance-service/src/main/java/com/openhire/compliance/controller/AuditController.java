package com.openhire.compliance.controller;

import com.openhire.compliance.domain.AuditChain;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.AuditLedger;
import com.openhire.compliance.service.port.CompliancePorts.AuditFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {
    private final AuditLedger ledger;
    private final Viewers viewers;
    private final ObjectMapper json;

    AuditController(AuditLedger ledger, Viewers viewers, ObjectMapper json) {
        this.ledger = ledger;
        this.viewers = viewers;
        this.json = json;
    }

    @GetMapping
    AuditLedger.Page list(
            Authentication auth,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String candidateId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        var tenant = viewers.require(auth, Viewers.READERS);
        var filter = new AuditFilter(
                blank(actor),
                blank(action),
                blank(resourceType),
                blank(resourceId),
                blank(candidateId),
                null,
                Times.normalise(from),
                Times.normalise(to));
        return ledger.list(tenant, filter, cursor, limit);
    }

    @GetMapping("/verify")
    AuditChain.Result verify(Authentication auth) {
        return ledger.verify(viewers.require(auth, Viewers.READERS));
    }

    @GetMapping("/export")
    ResponseEntity<StreamingResponseBody> export(
            Authentication auth,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        var tenant = viewers.require(auth, Viewers.READERS);
        var actor = viewers.actor(auth);
        StreamingResponseBody body = out -> ledger.export(tenant, actor, from, to, entry -> {
            try {
                out.write((json.writeValueAsString(entry.view()) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("Content-Disposition", "attachment; filename=\"audit-events.ndjson\"")
                .body(body);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
