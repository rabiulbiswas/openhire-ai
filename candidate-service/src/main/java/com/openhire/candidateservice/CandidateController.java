package com.openhire.candidateservice;

import com.openhire.support.Access;
import com.openhire.support.Records;
import java.util.Map;
import org.bson.Document;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CandidateController {
    private final CandidateService service;
    private final Access access;

    public CandidateController(CandidateService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/internal/candidates/resolve")
    public Document resolve(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Document input) {
        access.internal(secret);
        return service.resolve(input);
    }

    @PostMapping("/internal/candidates/project")
    public Document project(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody Document input) {
        access.internal(secret);
        return service.project(input);
    }

    @GetMapping("/internal/candidates/{id}/canonical")
    public Document canonical(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @PathVariable String id,
            @RequestParam String tenantId) {
        access.internal(secret);
        return new Document("id", service.canonical(tenantId, id).getString("_id"));
    }

    @GetMapping("/candidates/{id}/360")
    public Document get(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        return service.view(access.tenant(auth), id);
    }

    @PutMapping("/candidates/{id}/owner")
    public Map<String, String> owner(Authentication auth, @PathVariable String id, @RequestBody Document body) {
        access.requireStaff(auth);
        service.owner(access.tenant(auth), id, Records.required(body, "owner", 100));
        return Map.of("status", "UPDATED");
    }

    @GetMapping("/merge-suggestions")
    public Object suggestions(Authentication auth) {
        access.requireStaff(auth);
        return Map.of("data", service.suggestions(access.tenant(auth)));
    }

    @PostMapping("/merge-suggestions/{id}/dismiss")
    public Object dismiss(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        service.dismiss(access.tenant(auth), id);
        return Map.of("status", "DISMISSED");
    }

    @PostMapping("/candidates/merge")
    public Document merge(Authentication auth, @RequestBody Document body) {
        access.requireStaff(auth);
        return service.merge(
                access.tenant(auth),
                access.actor(auth),
                Records.required(body, "suggestionId", 100),
                Records.doc(body.get("keep")));
    }
}
