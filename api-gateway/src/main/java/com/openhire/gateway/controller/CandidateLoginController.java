package com.openhire.gateway.controller;

import com.openhire.gateway.security.CandidateLoginReturn;
import com.openhire.gateway.security.TenantPortalAccess;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@RestController
public class CandidateLoginController {
    private final TenantPortalAccess portals;
    public CandidateLoginController(TenantPortalAccess portals) { this.portals = portals; }
    @GetMapping("/bff/candidate-login")
    Mono<ResponseEntity<Void>> login(ServerWebExchange exchange, @RequestParam(required=false) String returnTo) {
        if (!portals.enabled() || "admin".equals(portals.slug(exchange))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var destination = CandidateLoginReturn.safe(returnTo);
        return exchange.getSession().map(session -> {
            session.getAttributes().put(CandidateLoginReturn.KEY, destination);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/oauth2/authorization/" + portals.registration(exchange))).<Void>build();
        });
    }
}
