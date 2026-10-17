package com.openhire.applicationservice.adapter.in.web;

import com.openhire.applicationservice.application.IntakeService;
import com.openhire.applicationservice.domain.ApplicationRecord;
import com.openhire.applicationservice.domain.ApplicationStage;
import com.openhire.applicationservice.domain.IntakeCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Staff intake milestone. Public and partner intake are deliberately not exposed yet. */
@RestController
@RequestMapping("/api/v1/intake/applications")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
public class IntakeController {
    private final IntakeService service;
    private final String securityMode;
    private final String localTenant;

    public IntakeController(
            IntakeService service,
            @Value("${openhire.security.mode:oidc}") String securityMode,
            @Value("${openhire.tenant.default-id:}") String localTenant) {
        this.service = service;
        this.securityMode = securityMode;
        this.localTenant = localTenant;
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<ApplicationView> submit(
            Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestBody IntakeCommand command) {
        var record = service.submit(
                tenant(auth),
                actor(auth),
                key,
                command,
                auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getTokenValue() : null);
        return ResponseEntity.ok().eTag(record.revision().toString()).body(ApplicationView.of(record));
    }

    @GetMapping("/{id}")
    ResponseEntity<ApplicationView> get(Authentication auth, @PathVariable String id) {
        var record = service.get(tenant(auth), id);
        return ResponseEntity.ok().eTag(record.revision().toString()).body(ApplicationView.of(record));
    }

    @GetMapping
    Page list(
            Authentication auth,
            @RequestParam(required = false) String jobId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return new Page(
                service.list(tenant(auth), jobId, page, size).stream()
                        .map(ApplicationView::of)
                        .toList(),
                page,
                size);
    }

    @PatchMapping("/{id}/stage")
    ResponseEntity<ApplicationView> transition(
            Authentication auth,
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody StageChange request) {
        if (!ifMatch.matches("(?:[0-9]+|\"[0-9]+\")"))
            throw new IllegalArgumentException("If-Match must contain a revision");
        long revision;
        try {
            revision = Long.parseLong(ifMatch.replace("\"", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid If-Match revision");
        }
        var record = service.transition(tenant(auth), actor(auth), id, revision, request.stage(), request.reason());
        return ResponseEntity.ok().eTag(record.revision().toString()).body(ApplicationView.of(record));
    }

    private String tenant(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            var tenant = jwt.getToken().getClaimAsString("organization_id");
            if (tenant == null || tenant.isBlank()) tenant = jwt.getToken().getClaimAsString("tenant_id");
            if (tenant != null && !tenant.isBlank()) return tenant;
        }
        if ("local".equals(securityMode) && !localTenant.isBlank()) return localTenant;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Token must identify an organization");
    }

    private String actor(Authentication auth) {
        return auth == null ? "local-recruiter" : auth.getName();
    }

    public record StageChange(@NotNull ApplicationStage stage, String reason) {}

    public record Page(List<ApplicationView> data, int page, int size) {}

    public record ApplicationView(
            String id,
            String jobId,
            String jobTitle,
            int jobVersion,
            String name,
            String email,
            String owner,
            String source,
            String externalReference,
            boolean processingConsent,
            String consentVersion,
            String consentRecordedBy,
            ApplicationStage stage,
            Instant submittedAt,
            Instant updatedAt,
            Long revision,
            List<ApplicationRecord.Decision> decisions) {
        static ApplicationView of(ApplicationRecord record) {
            var input = record.intake();
            return new ApplicationView(
                    record.id(),
                    input.jobId(),
                    record.jobTitle(),
                    record.jobVersion(),
                    input.name(),
                    input.email(),
                    input.owner(),
                    "RECRUITER",
                    input.externalReference(),
                    input.processingConsent(),
                    input.consentVersion(),
                    record.submittedBy(),
                    record.stage(),
                    record.submittedAt(),
                    record.updatedAt(),
                    record.revision(),
                    record.decisions());
        }
    }
}
