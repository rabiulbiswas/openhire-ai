package com.openhire.offer.controller;

import com.openhire.offer.service.OfferApprovalInbox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/offers")
class OfferApprovalController {
    private final OfferApprovalInbox inbox;
    private final Viewers viewers;

    OfferApprovalController(OfferApprovalInbox inbox, Viewers viewers) {
        this.inbox = inbox;
        this.viewers = viewers;
    }

    record DecisionInput(@NotBlank String stepId, @Min(1) int version, @Size(max = 4000) String reason) {}

    @GetMapping("/approval-inbox")
    Object list(Authentication auth, @RequestParam(defaultValue = "waiting_on_me") String scope) {
        return inbox.list(viewers.tenant(auth), viewers.viewer(auth), scope);
    }

    @GetMapping("/approval-inbox/counts")
    Object counts(Authentication auth) {
        return inbox.counts(viewers.tenant(auth), viewers.viewer(auth));
    }

    @GetMapping("/{id}/approval-inbox")
    Object get(Authentication auth, @PathVariable String id) {
        return inbox.get(viewers.tenant(auth), viewers.viewer(auth), id);
    }

    @PostMapping("/{id}/approval-inbox/approve")
    void approve(Authentication auth, @PathVariable String id, @Valid @RequestBody DecisionInput input) {
        inbox.decide(viewers.tenant(auth), viewers.viewer(auth), id, input.stepId(), input.version(), null, true);
    }

    @PostMapping("/{id}/approval-inbox/reject")
    void reject(Authentication auth, @PathVariable String id, @Valid @RequestBody DecisionInput input) {
        inbox.decide(
                viewers.tenant(auth), viewers.viewer(auth), id, input.stepId(), input.version(), input.reason(), false);
    }
}
