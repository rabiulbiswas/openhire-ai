package com.openhire.talent.controller;

import com.openhire.talent.dto.TalentViews.ConsentView;
import com.openhire.talent.service.ConsentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Candidate portal: the application receipt is the credential for the "Consider me for future roles" switch. */
@RestController
@RequestMapping("/api/v1/public/applications/{applicationId}/talent-consent")
class PublicConsentController {
    private final ConsentService consent;

    PublicConsentController(ConsentService consent) {
        this.consent = consent;
    }

    record ConsentInput(Boolean granted) {}

    @GetMapping
    ConsentView get(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return consent.current(tenantId, applicationId, receipt);
    }

    @PutMapping
    ConsentView put(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt,
            @RequestBody ConsentInput input) {
        if (input == null || input.granted() == null) throw new IllegalArgumentException("granted is required");
        return consent.set(tenantId, applicationId, receipt, input.granted());
    }
}
