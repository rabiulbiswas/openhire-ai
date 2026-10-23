package com.openhire.compliance.controller;

import com.openhire.compliance.service.ConsentService;
import com.openhire.compliance.service.PrivacyService;
import com.openhire.compliance.service.SelfIdentificationService;
import com.openhire.support.Access;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Candidate-facing endpoints (the application receipt is the credential) and the internal consent lookup. */
@RestController
@RequestMapping("/api/v1")
class PublicController {
    private final PrivacyService privacy;
    private final SelfIdentificationService selfIdentification;
    private final ConsentService consent;
    private final Access access;

    PublicController(
            PrivacyService privacy,
            SelfIdentificationService selfIdentification,
            ConsentService consent,
            Access access) {
        this.privacy = privacy;
        this.selfIdentification = selfIdentification;
        this.consent = consent;
        this.access = access;
    }

    record TypeInput(String type) {}

    @PostMapping("/public/applications/{applicationId}/privacy-requests")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> request(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt,
            @RequestBody TypeInput input) {
        return privacy.createByCandidate(tenantId, applicationId, receipt, input.type());
    }

    @GetMapping("/public/applications/{applicationId}/privacy-requests")
    List<Map<String, Object>> requests(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return privacy.candidateRequests(tenantId, applicationId, receipt);
    }

    @GetMapping("/public/privacy-requests/{id}/export")
    Map<String, Object> export(
            @PathVariable String id,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return privacy.candidateExport(tenantId, id, receipt);
    }

    @PutMapping("/public/applications/{applicationId}/self-identification")
    Map<String, Object> putSelfIdentification(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt,
            @RequestBody Map<String, Object> input) {
        return selfIdentification.put(tenantId, applicationId, receipt, input);
    }

    @GetMapping("/public/applications/{applicationId}/self-identification")
    Map<String, Object> getSelfIdentification(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return selfIdentification.get(tenantId, applicationId, receipt);
    }

    @GetMapping("/internal/consent/active")
    Map<String, Object> activeConsent(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @RequestParam String tenantId,
            @RequestParam(defaultValue = "PROCESSING") String purpose) {
        access.internal(secret);
        return consent.active(tenantId, purpose);
    }
}
