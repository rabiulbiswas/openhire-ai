package com.openhire.applicationservice.controller;

import static com.openhire.support.Records.*;

import com.openhire.applicationservice.service.AiAssessments;
import com.openhire.applicationservice.service.Answers;
import com.openhire.applicationservice.service.SubmissionService;
import com.openhire.support.Access;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class SubmissionController {
    private final SubmissionService service;
    private final Access access;

    public SubmissionController(SubmissionService service, Access access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/internal/applications/{id}/projection")
    public Document projection(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @PathVariable String id,
            @RequestParam String tenantId) {
        access.internal(secret);
        var row = service.get(tenantId, id);
        return new Document("tenantId", tenantId)
                .append("candidateId", text(row, "candidateId"))
                .append("applicationId", id)
                .append("jobId", text(row, "jobId"))
                .append("jobTitle", text(row, "jobTitle"))
                .append("stage", text(row, "stage"))
                .append("appliedAt", text(row, "submittedAt"))
                .append("updatedAt", text(row, "updatedAt"))
                .append("revision", row.get("revision"))
                .append("resumeId", text(row, "resumeId"))
                .append("resumeName", text(row, "resumeName"))
                .append("candidateName", text(row, "name"))
                .append("candidateEmail", text(row, "email"))
                .append("candidatePhone", text(row, "phone"))
                .append("source", text(row, "source"))
                .append("actorId", text(row, "actorId"))
                .append("owner", text(row, "owner"));
    }

    /** Lets another service accept the candidate's application receipt without holding receipt hashes itself. */
    @PostMapping("/internal/applications/{id}/receipt-check")
    public Document receiptCheck(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @PathVariable String id,
            @RequestBody Document body) {
        access.internal(secret);
        var row = receipt(required(body, "tenantId", 100), id, required(body, "receipt", 512));
        return new Document("applicationId", id)
                .append("candidateId", text(row, "candidateId"))
                .append("candidateName", text(row, "name"));
    }

    @GetMapping("/applications/{id}/decision")
    public Document decision(Authentication auth, @PathVariable String id) {
        requireDecisionMaker(auth);
        return service.decision(access.tenant(auth), id);
    }

    @PostMapping("/applications/{id}/decision")
    public Document decide(Authentication auth, @PathVariable String id, @RequestBody Document body) {
        requireDecisionMaker(auth);
        service.decide(
                access.tenant(auth),
                id,
                access.actor(auth),
                displayName(auth),
                required(body, "decision", 10),
                text(body, "reason"));
        return service.decision(access.tenant(auth), id);
    }

    private String displayName(Authentication auth) {
        if (auth
                instanceof
                org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            var name = jwt.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) return name;
        }
        return access.actor(auth);
    }

    private void requireDecisionMaker(Authentication auth) {
        if (!access.staff(auth) && !access.role(auth, "HIRING_MANAGER"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recruiter or hiring manager access required");
    }

    @GetMapping("/public/consent")
    public Object consent(Authentication auth, @RequestParam(required = false) String tenantId) {
        // Authenticated requests use their verified tenant, not a configured workspace or query override.
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
            var verifiedTenant = access.tenant(auth);
            if (tenantId != null && !tenantId.isBlank() && !verifiedTenant.equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant does not match authenticated identity");
            }
            tenantId = verifiedTenant;
        }
        var active = service.consent(tenantId);
        return Map.of("version", active.version(), "text", active.text());
    }

    @PostMapping(value = "/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object submit(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam Map<String, String> fields,
            @RequestPart(required = false) MultipartFile resume)
            throws Exception {
        String tenant = access.tenant(auth), actor = access.actor(auth);
        var input = new Document();
        input.putAll(fields);
        authorizeSource(auth, tenant, actor, input);
        if (fields.containsKey("answers")) input.put("answers", Answers.parse(fields.get("answers")));
        var row = service.submit(tenant, actor, key, input, resume);
        return Map.of("id", row.getString("_id"), "stage", row.getString("stage"));
    }

    @PostMapping(value = "/applications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object json(Authentication auth, @RequestHeader("Idempotency-Key") String key, @RequestBody Document input)
            throws Exception {
        String tenant = access.tenant(auth), actor = access.actor(auth);
        authorizeSource(auth, tenant, actor, input);
        return Map.of("id", service.submit(tenant, actor, key, input, null).getString("_id"));
    }

    private void authorizeSource(Authentication auth, String tenant, String actor, Document input) {
        String source = SubmissionService.source(text(input, "source"));
        input.put("source", source);
        if (source.equals("CAREER"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Use career application flow");
        if (access.staff(auth)) return;
        if (source.equals("AGENCY") && access.role(auth, "AGENCY_RECRUITER")) {
            if (!service.store()
                    .exists(
                            Query.query(Criteria.where("tenantId")
                                    .is(tenant)
                                    .and("actorId")
                                    .is(actor)
                                    .and("jobId")
                                    .is(text(input, "jobId"))
                                    .and("active")
                                    .is(true)),
                            "agency_assignments"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Job is not assigned to this agency account");
            input.put("owner", "");
            return;
        }
        if (Set.of("INTERNAL", "REFERRAL").contains(source) && access.role(auth, "EMPLOYEE")) {
            input.put("owner", "");
            if (source.equals("INTERNAL")) {
                if (!(auth
                                instanceof
                                org.springframework.security.oauth2.server.resource.authentication
                                                .JwtAuthenticationToken
                                        jwt)
                        || !text(input, "email").equalsIgnoreCase(jwt.getToken().getClaimAsString("email")))
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN, "Internal applications must use your account email");
            }
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Intake channel not allowed for this account");
    }

    @PostMapping(value = "/public/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object career(
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Public-Tenant-Id", required = false) String hostTenant,
            @RequestParam Map<String, String> fields,
            @RequestPart MultipartFile resume)
            throws Exception {
        // Receipt-based tracking/withdrawal still works, but anonymous intake is retired.
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Register and sign in with a verified candidate account before applying");
    }

    private Document receipt(String tenant, String id, String token) {
        var row = service.get(tenant, id);
        boolean original = java.security.MessageDigest.isEqual(
                hash(token).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                text(row, "receiptHash").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean login = row.getList("portalReceipts", Document.class, java.util.List.of()).stream()
                .anyMatch(grant -> {
                    try {
                        return java.time.Instant.parse(text(grant, "expiresAt")).isAfter(java.time.Instant.now())
                                && java.security.MessageDigest.isEqual(
                                        hash(token).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        text(grant, "hash").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (RuntimeException invalid) {
                        return false;
                    }
                });
        if (!original && !login) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid application receipt");
        return row;
    }

    @GetMapping("/public/applications/{id}")
    public Object status(
            @PathVariable String id,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String token) {
        var row = receipt(tenantId, id, token);
        return Map.of(
                "id",
                id,
                "name",
                text(row, "name"),
                "email",
                text(row, "email"),
                "phone",
                text(row, "phone"),
                "jobTitle",
                text(row, "jobTitle"),
                "stage",
                text(row, "stage"),
                "submittedAt",
                text(row, "submittedAt"));
    }

    @PostMapping("/public/applications/{id}/withdraw")
    public Object withdraw(
            @PathVariable String id,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String token) {
        var row = receipt(tenantId, id, token);
        service.withdraw(tenantId, id, row.getString("actorId"), "Withdrawn by applicant");
        return Map.of("status", "WITHDRAWN");
    }

    @GetMapping("/applications")
    public Object list(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        access.requireStaff(auth);
        var rows = service.list(access.tenant(auth), null, null, status, page, size);
        if ("NEEDS_ATTENTION".equals(status))
            return Map.of(
                    "data",
                    rows.stream()
                            .map(r -> new Document("applicationId", r.getString("_id"))
                                    .append("candidateName", text(r, "name"))
                                    .append("jobTitle", text(r, "jobTitle"))
                                    .append("reason", text(r, "detail"))
                                    .append("code", text(r, "attentionCode"))
                                    .append(
                                            "action",
                                            Set.of(
                                                                    "MISSING_RESUME",
                                                                    "MALWARE_DETECTED",
                                                                    "UPLOAD_FAILED",
                                                                    "ATTEMPTS_EXHAUSTED")
                                                            .contains(text(r, "attentionCode"))
                                                    ? "UPLOAD"
                                                    : "KNOCKOUT_REVIEW".equals(text(r, "attentionCode"))
                                                            ? "CONFIRM_NOT_ELIGIBLE"
                                                            : "RESCORE")
                                    .append("actionLabel", "Retry processing"))
                            .toList());
        return Map.of("data", rows.stream().map(service::card).toList());
    }

    @GetMapping("/jobs/{id}/applications")
    public Object byJob(
            Authentication auth,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String channel) {
        access.requireStaff(auth);
        return Map.of(
                "data",
                service.list(access.tenant(auth), id, null, null, page, size, channel).stream()
                        .map(service::card)
                        .toList());
    }

    @GetMapping("/jobs/{id}/pipeline")
    public Object pipeline(Authentication auth, @PathVariable String id, @RequestParam(defaultValue = "0") int page) {
        access.requireStaff(auth);
        var rows = service.list(access.tenant(auth), id, null, null, page, 100);
        return Map.of(
                "jobId",
                id,
                "jobTitle",
                text(service.job(access.tenant(auth), id), "title"),
                "columns",
                SubmissionService.STAGES.stream()
                        .map(stage -> Map.of(
                                "stage",
                                stage,
                                "cards",
                                rows.stream()
                                        .filter(r -> stage.equals(text(r, "stage")))
                                        .map(service::card)
                                        .toList()))
                        .toList(),
                "page",
                page,
                "hasMore",
                rows.size() == 100,
                "limit",
                100);
    }

    @GetMapping("/applications/{id}/assessment")
    public Object assessment(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        var row = service.get(access.tenant(auth), id);
        if (!row.containsKey("assessment")) throw conflict("Assessment is not ready");
        return AiAssessments.view(row);
    }

    @PatchMapping("/applications/{id}/stage")
    public Object stage(Authentication auth, @PathVariable String id, @RequestBody Document body) {
        access.requireStaff(auth);
        return service.card(service.move(
                access.tenant(auth),
                id,
                access.actor(auth),
                required(body, "stage", 40),
                "Stage changed by recruiter",
                null));
    }

    @PostMapping("/applications/{id}/shortlist")
    public Object shortlist(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        return service.card(service.move(
                access.tenant(auth), id, access.actor(auth), "SHORTLISTED", "Reviewed by recruiter", null));
    }

    @PostMapping("/applications/{id}/reject")
    public Object reject(Authentication auth, @PathVariable String id, @RequestBody Document body) {
        access.requireStaff(auth);
        return service.card(service.move(
                access.tenant(auth),
                id,
                access.actor(auth),
                "REJECTED",
                required(body, "reason", 1000),
                null,
                rejectionCode(body)));
    }

    private static String rejectionCode(Document body) {
        String code = text(body, "reasonCode");
        if (code.isBlank()) return "UNSPECIFIED";
        if (!code.matches("[A-Z][A-Z0-9_]{1,39}")) throw new IllegalArgumentException("Invalid reasonCode");
        return code;
    }

    @PostMapping("/applications/{id}/undo-decision")
    public Object undo(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        service.undo(access.tenant(auth), id, access.actor(auth));
        return Map.of("status", "UNDONE");
    }

    @PostMapping("/applications/{id}/retry")
    public Object retry(Authentication auth, @PathVariable String id) {
        access.requireStaff(auth);
        service.retry(access.tenant(auth), id);
        return Map.of("status", "QUEUED");
    }

    @PostMapping("/applications/{id}/eligibility")
    public Object eligibility(Authentication auth, @PathVariable String id, @RequestBody Document body) {
        access.requireStaff(auth);
        service.reviewEligibility(access.tenant(auth), id, access.actor(auth), required(body, "reason", 1000));
        return Map.of("status", "QUEUED");
    }

    @PostMapping("/applications/{id}/resume")
    public Object replace(Authentication auth, @PathVariable String id, @RequestPart MultipartFile resume)
            throws Exception {
        access.requireStaff(auth);
        service.replaceResume(access.tenant(auth), id, resume);
        return Map.of("status", "QUEUED");
    }

    @PostMapping("/assessments/{id}/override")
    public Object override(Authentication auth, @PathVariable String id, @RequestBody Document input) {
        access.requireStaff(auth);
        int score = ((Number) input.getOrDefault("newScore", -1)).intValue();
        String code = text(input, "reasonCode").isBlank() ? "OTHER" : text(input, "reasonCode");
        if (!SubmissionService.OVERRIDE_REASONS.contains(code))
            throw new IllegalArgumentException("Unknown reasonCode");
        service.overrideScore(
                access.tenant(auth), id, access.actor(auth), score, required(input, "reason", 1000), code);
        return Map.of("status", "UPDATED");
    }

    @PutMapping("/partner-assignments/{actorId}/{jobId}")
    public Object assignment(
            Authentication auth,
            @PathVariable String actorId,
            @PathVariable String jobId,
            @RequestBody Document input) {
        access.requireStaff(auth);
        String tenant = access.tenant(auth);
        service.job(tenant, jobId);
        service.store()
                .upsert(
                        Query.query(Criteria.where("_id").is(hash(tenant, actorId, jobId))),
                        new Update()
                                .set("tenantId", tenant)
                                .set("actorId", actorId)
                                .set("jobId", jobId)
                                .set("active", Boolean.TRUE.equals(input.get("active"))),
                        "agency_assignments");
        return Map.of("status", "UPDATED");
    }
}
