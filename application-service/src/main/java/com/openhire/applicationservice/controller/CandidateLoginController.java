package com.openhire.applicationservice.controller;

import static com.openhire.support.Records.*;

import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Exchanges verified candidate identity for a scoped, expiring application capability. */
@RestController
@RequestMapping("/api/v1/applications/me")
public class CandidateLoginController {
    private final SubmissionService service;
    private final Access access;
    private final SecureRandom random = new SecureRandom();

    public CandidateLoginController(SubmissionService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping("/candidate-portal")
    public Map<String, Object> open(Authentication auth) {
        if (!access.role(auth, "CANDIDATE")
                || !(auth instanceof JwtAuthenticationToken jwt)
                || !Boolean.TRUE.equals(jwt.getToken().getClaim("email_verified")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified candidate account required");
        var email = jwt.getToken().getClaimAsString("email");
        if (email == null || email.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified email required");
        var tenant = access.tenant(auth);
        var query = Query.query(Criteria.where("tenantId")
                        .is(tenant)
                        .and("email")
                        .is(email.trim().toLowerCase(java.util.Locale.ROOT))
                        .and("source")
                        .is("CAREER")
                        .and("processingConsent")
                        .is(true))
                .with(Sort.by(Sort.Direction.DESC, "submittedAt", "_id"))
                .limit(1);
        var row = service.store().findOne(query, Document.class, "submissions");
        if (row == null)
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No application is linked to this candidate account");
        var bytes = new byte[48];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expires = Instant.now().plusSeconds(86400).toString();
        // Keep original email links working; retain at most five recent login capabilities.
        service.store()
                .updateFirst(
                        service.key(tenant, text(row, "_id")),
                        new Update()
                                .push("portalReceipts")
                                .slice(-5)
                                .each(new Document("hash", hash(token)).append("expiresAt", expires)),
                        "submissions");
        return Map.of("id", text(row, "_id"), "tenantId", tenant, "token", token, "expiresAt", expires);
    }
}
