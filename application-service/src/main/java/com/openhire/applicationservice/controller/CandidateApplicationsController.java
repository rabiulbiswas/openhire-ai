package com.openhire.applicationservice.controller;

import static com.openhire.support.Records.*;

import com.openhire.applicationservice.service.Answers;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.platform.security.CandidateIdentity;
import com.openhire.support.ServiceClient;
import java.util.Map;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Candidate-owned intake. The browser cannot choose a tenant, identity, role, or account email. */
@RestController
@RequestMapping("/api/v1/candidate/applications")
public class CandidateApplicationsController {
    private final SubmissionService service;
    private final ServiceClient identity;
    public CandidateApplicationsController(SubmissionService service,
            @Value("${openhire.identity-url:http://localhost:8081}") String identityUrl,
            @Value("${openhire.internal-secret:local-development-secret}") String secret) {
        this.service = service; this.identity = new ServiceClient(identityUrl, secret);
    }

    @GetMapping("/status")
    public Map<String, Object> status(Authentication auth, @RequestParam String jobId) {
        var who = CandidateIdentity.require(auth); validateJobId(jobId);
        return Map.of("applied", service.store().exists(applicationQuery(who, jobId), "submissions"));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object submit(Authentication auth, @RequestHeader("Idempotency-Key") String clientKey,
            @RequestParam Map<String, String> fields, @RequestPart MultipartFile resume) throws Exception {
        var who = CandidateIdentity.require(auth);
        if (clientKey.isBlank() || clientKey.length() > 200) throw new IllegalArgumentException("Invalid request identifier");
        var input = new Document(); input.putAll(fields);
        var job = required(input, "jobId", 100); validateJobId(job);
        var receipt = required(input, "receiptToken", 200);
        if (receipt.length() < 32) throw new IllegalArgumentException("A secure receipt is required");
        var actor = "candidate:" + who.subject();
        // The same candidate/job has one deterministic storage key, across tabs, devices and client keys.
        var stableKey = hash("candidate-job", who.tenantId(), who.subject(), job);
        var expectedId = hash(who.tenantId(), actor, "CAREER", "idempotency", stableKey);
        var previous = service.store().findOne(applicationQuery(who, job), Document.class, "submissions");
        if (previous != null && (!expectedId.equals(text(previous, "_id")) || !hash(receipt).equals(text(previous, "receiptHash"))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already applied for this job");
        var profile = identity.raw().get().uri(builder -> builder.path("/api/v1/internal/candidates/{subject}/profile")
                        .queryParam("tenantId", who.tenantId()).build(who.subject())).retrieve().body(Document.class);
        if (profile == null || !who.email().equalsIgnoreCase(text(profile, "email")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Candidate account profile does not match");
        input.put("name", text(profile, "name")); input.put("email", who.email());
        input.put("phone", text(profile, "phone")); input.put("location", text(profile, "location"));
        input.remove("owner"); input.remove("sourceProvider"); input.remove("externalReference");
        input.put("source", "CAREER"); input.put("answers", fields.containsKey("answers") ? Answers.parse(fields.get("answers")) : new Document());
        var row = service.submit(who.tenantId(), actor, stableKey, input, resume);
        if (!hash(receipt).equals(text(row, "receiptHash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already applied for this job");
        return Map.of("id", row.getString("_id"), "stage", row.getString("stage"));
    }
    private Query applicationQuery(CandidateIdentity who, String job) {
        return Query.query(Criteria.where("tenantId").is(who.tenantId()).and("email").is(who.email()).and("jobId").is(job).and("source").is("CAREER"));
    }
    private void validateJobId(String id) { if (id == null || id.isBlank() || id.length() > 100) throw new IllegalArgumentException("Job is required"); }
}
