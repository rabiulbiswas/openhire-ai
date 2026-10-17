package com.openhire.compliance.adapter.in.web;

import com.openhire.compliance.application.ConsentService;
import com.openhire.compliance.application.DecisionHistory;
import com.openhire.compliance.application.FairnessService;
import com.openhire.compliance.application.LegalHolds;
import com.openhire.compliance.application.OverviewService;
import com.openhire.compliance.application.PrivacyService;
import com.openhire.compliance.application.RetentionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff endpoints other than the audit ledger. Every method starts with the role check. */
@RestController
@RequestMapping("/api/v1")
class StaffController {
    private final Viewers viewers;
    private final DecisionHistory decisions;
    private final ConsentService consent;
    private final PrivacyService privacy;
    private final LegalHolds holds;
    private final RetentionService retention;
    private final FairnessService fairness;
    private final OverviewService overview;

    StaffController(
            Viewers viewers,
            DecisionHistory decisions,
            ConsentService consent,
            PrivacyService privacy,
            LegalHolds holds,
            RetentionService retention,
            FairnessService fairness,
            OverviewService overview) {
        this.viewers = viewers;
        this.decisions = decisions;
        this.consent = consent;
        this.privacy = privacy;
        this.holds = holds;
        this.retention = retention;
        this.fairness = fairness;
        this.overview = overview;
    }

    record ConsentInput(String purpose, String locale, String text) {}

    record PrivacyInput(String candidateId, String type, String reason) {}

    record HoldInput(String candidateId, String jobId, String reason, String expiresAt) {}

    record ReasonInput(String reason) {}

    record PolicyInput(Integer retentionMonths, Boolean enabled) {}

    // ---- AI decisions and trace ----

    @GetMapping("/ai-decisions")
    List<Map<String, Object>> aiDecisions(
            Authentication auth,
            @RequestParam(required = false) String candidateId,
            @RequestParam(required = false) String applicationId,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String from,
            @RequestParam(defaultValue = "50") int limit) {
        var tenant = viewers.require(auth, Viewers.READERS);
        return decisions.list(tenant, blank(candidateId), blank(applicationId), blank(task), from, limit);
    }

    @GetMapping("/ai-decisions/{id}")
    Map<String, Object> aiDecision(Authentication auth, @PathVariable String id) {
        return decisions.get(viewers.require(auth, Viewers.READERS), id);
    }

    @GetMapping("/trace/applications/{applicationId}")
    List<Map<String, Object>> trace(Authentication auth, @PathVariable String applicationId) {
        return decisions.trace(viewers.require(auth, Viewers.READERS), applicationId);
    }

    // ---- consent ----

    @GetMapping("/consent-texts")
    List<Map<String, Object>> consentTexts(Authentication auth) {
        return consent.texts(viewers.require(auth, Viewers.READERS));
    }

    @PostMapping("/consent-texts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> publishConsent(Authentication auth, @RequestBody ConsentInput input) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return consent.publish(tenant, viewers.actor(auth), input.purpose(), input.locale(), input.text());
    }

    @GetMapping("/consent-records")
    List<Map<String, Object>> consentRecords(Authentication auth, @RequestParam(required = false) String candidateId) {
        return consent.records(viewers.require(auth, Viewers.READERS), candidateId);
    }

    // ---- privacy requests ----

    @GetMapping("/privacy/requests")
    List<Map<String, Object>> privacyRequests(Authentication auth, @RequestParam(required = false) String status) {
        return privacy.list(viewers.require(auth, Viewers.MANAGERS), status);
    }

    @GetMapping("/privacy/requests/{id}")
    Map<String, Object> privacyRequest(Authentication auth, @PathVariable String id) {
        return privacy.get(viewers.require(auth, Viewers.MANAGERS), id);
    }

    @PostMapping("/privacy/requests")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createPrivacyRequest(Authentication auth, @RequestBody PrivacyInput input) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return privacy.createByStaff(tenant, viewers.actor(auth), input.candidateId(), input.type(), input.reason());
    }

    // ---- legal holds ----

    @GetMapping("/legal-holds")
    List<Map<String, Object>> legalHolds(Authentication auth, @RequestParam(required = false) String status) {
        return holds.list(viewers.require(auth, Viewers.MANAGERS), status);
    }

    @PostMapping("/legal-holds")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> placeHold(Authentication auth, @RequestBody HoldInput input) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return holds.place(
                tenant, viewers.actor(auth), input.candidateId(), input.jobId(), input.reason(), input.expiresAt());
    }

    @PostMapping("/legal-holds/{id}/release")
    Map<String, Object> releaseHold(Authentication auth, @PathVariable String id, @RequestBody ReasonInput input) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return holds.release(tenant, viewers.actor(auth), id, input == null ? null : input.reason());
    }

    // ---- retention ----

    @GetMapping("/retention-policies")
    List<Map<String, Object>> retentionPolicies(Authentication auth) {
        return retention.list(viewers.require(auth, Viewers.READERS));
    }

    @PutMapping("/retention-policies/{key}")
    Map<String, Object> updatePolicy(Authentication auth, @PathVariable String key, @RequestBody PolicyInput input) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return retention.update(tenant, viewers.actor(auth), key, input.retentionMonths(), input.enabled());
    }

    /** A dry run by default; {@code asOf} only applies to dry runs. */
    @PostMapping("/retention-policies/run")
    Map<String, Object> runRetention(
            Authentication auth,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(required = false) String asOf) {
        var tenant = viewers.require(auth, Viewers.MANAGERS);
        return retention.run(tenant, dryRun, asOf, viewers.actor(auth));
    }

    // ---- fairness and overview ----

    @GetMapping("/reports/fairness")
    Map<String, Object> fairnessReport(
            Authentication auth,
            @RequestParam(required = false) String jobId,
            @RequestParam(defaultValue = "gender") String dimension) {
        return fairness.report(viewers.require(auth, Viewers.READERS), blank(jobId), dimension);
    }

    @GetMapping("/compliance/overview")
    Map<String, Object> overview(Authentication auth) {
        return overview.overview(viewers.require(auth, Viewers.READERS));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
