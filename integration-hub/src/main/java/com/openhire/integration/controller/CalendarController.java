package com.openhire.integration.controller;

import com.openhire.integration.service.CalendarConnections;
import com.openhire.support.Access;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/connectors")
class CalendarController {
    private final CalendarConnections calendars;
    private final Access access;

    CalendarController(CalendarConnections calendars, Access access) {
        this.calendars = calendars;
        this.access = access;
    }

    private String tenant(Authentication auth) {
        if (!access.role(auth, "ORG_ADMIN") && !access.role(auth, "SUPER_ADMIN"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization administrator required");
        return access.tenant(auth);
    }

    @PostMapping("/{id}/authorize")
    Map<String, String> authorize(Authentication auth, @PathVariable String id) {
        return Map.of("authorizationUrl", calendars.authorize(tenant(auth), access.actor(auth), id));
    }

    record Callback(String state, String code) {}

    @PostMapping("/oauth/callback")
    Map<String, String> callback(Authentication auth, @RequestBody Callback callback) {
        calendars.complete(tenant(auth), access.actor(auth), callback.state(), callback.code());
        return Map.of("status", "AUTHORIZED");
    }

    @GetMapping("/{id}/connection")
    Map<String, String> status(Authentication auth, @PathVariable String id) {
        return Map.of("status", calendars.status(tenant(auth), id));
    }

    @GetMapping("/{id}/calendar/events")
    Map<String, Object> events(Authentication auth, @PathVariable String id) {
        return calendars.list(tenant(auth), id);
    }

    @DeleteMapping("/{id}/connection")
    Map<String, String> disconnect(Authentication auth, @PathVariable String id) {
        calendars.disconnect(tenant(auth), access.actor(auth), id);
        return Map.of(
                "status",
                "DISCONNECTED",
                "notice",
                "Local tokens deleted. Revoke provider consent separately if required.");
    }
}
