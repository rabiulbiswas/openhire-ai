package com.openhire.integration.controller;

import com.openhire.integration.domain.IntegrationConfig;
import com.openhire.integration.service.IntegrationService;
import com.openhire.support.Access;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class IntegrationController {
    record Input(
            String name,
            String provider,
            String endpoint,
            String secretRef,
            List<String> eventTypes,
            boolean enabled,
            Long version) {}

    record View(
            String id,
            String name,
            String provider,
            String endpoint,
            List<String> eventTypes,
            boolean enabled,
            long version,
            String status,
            boolean secretReferenceConfigured) {
        static View of(IntegrationConfig c) {
            return new View(
                    c.id(),
                    c.name(),
                    c.provider(),
                    c.endpoint(),
                    c.eventTypes(),
                    c.enabled(),
                    c.version(),
                    c.status(),
                    true);
        }
    }

    private final IntegrationService service;
    private final Access access;

    IntegrationController(IntegrationService service, Access access) {
        this.service = service;
        this.access = access;
    }

    private String tenant(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization administrator required");
        return access.tenant(auth);
    }

    @GetMapping("/connectors/catalog")
    List<Map<String, Object>> catalog(Authentication auth) {
        tenant(auth);
        return List.of(
                Map.of(
                        "id",
                        "GOOGLE_CALENDAR",
                        "name",
                        "Google Calendar",
                        "status",
                        "CONFIGURATION_ONLY",
                        "plannedScopes",
                        List.of("https://www.googleapis.com/auth/calendar.events")),
                Map.of(
                        "id",
                        "MICROSOFT_365",
                        "name",
                        "Microsoft 365",
                        "status",
                        "CONFIGURATION_ONLY",
                        "plannedScopes",
                        List.of("Calendars.ReadWrite", "offline_access")));
    }

    @GetMapping("/connectors")
    List<View> connectors(
            Authentication auth,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "200") int limit) {
        return service.page(tenant(auth), "CONNECTOR", after, limit).stream()
                .map(View::of)
                .toList();
    }

    @GetMapping("/webhooks")
    List<View> webhooks(
            Authentication auth,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "200") int limit) {
        return service.page(tenant(auth), "WEBHOOK", after, limit).stream()
                .map(View::of)
                .toList();
    }

    @PostMapping("/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    View createConnector(Authentication auth, @RequestBody Input input) {
        return save(auth, "CONNECTOR", null, input);
    }

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    View createWebhook(Authentication auth, @RequestBody Input input) {
        return save(auth, "WEBHOOK", null, input);
    }

    @PutMapping("/connectors/{id}")
    View updateConnector(Authentication auth, @PathVariable String id, @RequestBody Input input) {
        return save(auth, "CONNECTOR", id, input);
    }

    @PutMapping("/webhooks/{id}")
    View updateWebhook(Authentication auth, @PathVariable String id, @RequestBody Input input) {
        return save(auth, "WEBHOOK", id, input);
    }

    private View save(Authentication auth, String kind, String id, Input input) {
        var tenant = tenant(auth);
        return View.of(service.save(
                tenant,
                access.actor(auth),
                kind,
                id,
                input.version(),
                input.name(),
                input.provider(),
                input.endpoint(),
                input.secretRef(),
                input.eventTypes(),
                input.enabled()));
    }

    @ExceptionHandler(IntegrationService.Conflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(IntegrationService.Conflict error) {
        return Map.of("detail", error.getMessage());
    }
}
