package com.openhire.jobservice.api;

import com.openhire.jobservice.api.JobCommands.DecisionRequest;
import com.openhire.jobservice.api.JobCommands.HiringTeamRequest;
import com.openhire.jobservice.api.JobCommands.InterviewPlanRequest;
import com.openhire.jobservice.application.JobApplicationService;
import com.openhire.platform.tenant.TenantContext;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    private final JobApplicationService service;
    private final String internalSecret;

    public JobController(
            JobApplicationService service,
            @Value("${openhire.internal-secret:local-development-secret}") String internalSecret) {
        this.service = service;
        this.internalSecret = internalSecret;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")
    PageResponse<JobSummaryResponse> list(@RequestParam(required = false) String status) {
        return service.list(tenant(), status);
    }

    @PostMapping("/ai-draft")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    AiJobDraftResponse draftBeforeCreate(@Valid @RequestBody AiJobDraftRequest request) {
        return service.draft(request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER','HIRING_MANAGER','INTERVIEWER')")
    ResponseEntity<JobResponse> get(@PathVariable String id) {
        return versioned(service.get(tenant(), id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> create(
            @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateJobRequest request) {
        var response = service.create(tenant(), actor, idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/jobs/" + response.id()))
                .eTag(String.valueOf(response.currentVersion().version()))
                .body(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> update(
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody CreateJobRequest request) {
        return versioned(service.update(tenant(), id, version(ifMatch), actor, request));
    }

    @PutMapping("/{id}/hiring-team")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> updateHiringTeam(
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody HiringTeamRequest request) {
        return versioned(service.updateHiringTeam(tenant(), id, version(ifMatch), actor, request.hiringTeam()));
    }

    @PutMapping("/{id}/versions/{version}/interview-plan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> updateInterviewPlan(
            @PathVariable String id,
            @PathVariable int version,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor,
            @Valid @RequestBody InterviewPlanRequest request) {
        return versioned(service.updateInterviewPlan(tenant(), id, version, actor, request.interviewPlan()));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> submit(
            @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return versioned(service.submit(tenant(), id, actor));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> publish(
            @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return versioned(service.publish(tenant(), id, actor));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> pause(
            @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return versioned(service.pause(tenant(), id, actor));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> close(
            @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return versioned(service.close(tenant(), id, actor));
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    ResponseEntity<JobResponse> reopen(
            @PathVariable String id, @RequestHeader(value = "X-Actor-Id", defaultValue = "local-admin") String actor) {
        return versioned(service.reopen(tenant(), id, actor));
    }

    @PostMapping("/{id}/versions/{version}/ai-draft")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','HR_ADMIN','RECRUITER')")
    AiJobDraftResponse draft(
            @PathVariable String id,
            @PathVariable int version,
            @RequestBody(required = false) java.util.Map<String, String> request) {
        var tone = request == null
                ? "clear, inclusive and direct"
                : request.getOrDefault("tone", "clear, inclusive and direct");
        return service.draft(tenant(), id, version, tone);
    }

    @PostMapping("/internal/{id}/approval-decision")
    JobResponse approvalDecision(
            @PathVariable String id,
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @Valid @RequestBody DecisionRequest request) {
        if (!internalSecret.equals(secret)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return service.decision(tenant(), id, request.decision(), request.actorId(), request.reason());
    }

    @GetMapping("/internal/{id}/intake")
    JobResponse intakeSnapshot(@PathVariable String id, @RequestHeader("X-Internal-Service-Secret") String secret) {
        if (!java.security.MessageDigest.isEqual(
                internalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return service.get(tenant(), id);
    }

    private ResponseEntity<JobResponse> versioned(JobResponse response) {
        return ResponseEntity.ok()
                .eTag(String.valueOf(response.currentVersion().version()))
                .body(response);
    }

    private int version(String header) {
        try {
            return Integer.parseInt(header.replace("W/", "").replace("\"", "").trim());
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid If-Match header");
        }
    }

    private String tenant() {
        return TenantContext.requiredTenantId();
    }
}
