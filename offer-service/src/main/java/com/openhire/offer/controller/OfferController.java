package com.openhire.offer.controller;

import com.openhire.offer.dto.OfferViews.ClauseView;
import com.openhire.offer.dto.OfferViews.OfferDetail;
import com.openhire.offer.dto.OfferViews.Page;
import com.openhire.offer.dto.OfferViews.TemplateView;
import com.openhire.offer.service.OfferApplicationService;
import com.openhire.offer.service.OfferApplicationService.TermsInput;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OfferController {
    private final OfferApplicationService offers;
    private final Viewers viewers;

    OfferController(OfferApplicationService offers, Viewers viewers) {
        this.offers = offers;
        this.viewers = viewers;
    }

    record CreateInput(String templateKey, TermsInput terms, String coverNote) {}

    record UpdateInput(TermsInput terms, String coverNote) {}

    record ReasonInput(String reason) {}

    record SubmitInput(java.util.Map<String, String> assignees) {}

    record TemplateInput(String name, String body, List<String> clauseKeys) {}

    record ClauseInput(String title, String body) {}

    @GetMapping("/offers")
    com.openhire.offer.dto.OfferViews.OfferPage list(
            Authentication auth,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "") String before,
            @RequestParam(defaultValue = "25") int limit) {
        return offers.listPage(viewers.tenant(auth), viewers.viewer(auth), status, q, after, before, limit);
    }

    @GetMapping("/offers/{id}")
    OfferDetail get(Authentication auth, @PathVariable String id) {
        return offers.get(viewers.tenant(auth), viewers.viewer(auth), id);
    }

    @PostMapping("/applications/{applicationId}/offer")
    @ResponseStatus(HttpStatus.CREATED)
    OfferDetail create(
            Authentication auth, @PathVariable String applicationId, @RequestBody(required = false) CreateInput input) {
        var body = input == null ? new CreateInput(null, null, null) : input;
        return offers.create(
                viewers.tenant(auth),
                viewers.viewer(auth),
                applicationId,
                body.templateKey(),
                body.terms(),
                body.coverNote());
    }

    @GetMapping("/applications/{applicationId}/offer")
    OfferDetail forApplication(Authentication auth, @PathVariable String applicationId) {
        return offers.forApplication(viewers.tenant(auth), viewers.viewer(auth), applicationId);
    }

    @PatchMapping("/offers/{id}")
    OfferDetail update(Authentication auth, @PathVariable String id, @RequestBody UpdateInput input) {
        return offers.update(viewers.tenant(auth), viewers.viewer(auth), id, input.terms(), input.coverNote());
    }

    @PostMapping("/offers/{id}/submit")
    OfferDetail submit(Authentication auth, @PathVariable String id, @RequestBody(required = false) SubmitInput input) {
        return input == null
                ? offers.submit(viewers.tenant(auth), viewers.viewer(auth), id)
                : offers.submit(viewers.tenant(auth), viewers.viewer(auth), id, input.assignees());
    }

    @PutMapping("/offers/{id}/approval-assignees")
    OfferDetail assignApprovers(Authentication auth, @PathVariable String id, @RequestBody SubmitInput input) {
        return offers.assignApprovers(viewers.tenant(auth), viewers.viewer(auth), id, input.assignees());
    }

    @PostMapping("/offers/{id}/approvals/{stepId}/approve")
    OfferDetail approve(Authentication auth, @PathVariable String id, @PathVariable String stepId) {
        return offers.approve(viewers.tenant(auth), viewers.viewer(auth), id, stepId);
    }

    @PostMapping("/offers/{id}/approvals/{stepId}/reject")
    OfferDetail reject(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String stepId,
            @RequestBody(required = false) ReasonInput input) {
        return offers.reject(
                viewers.tenant(auth), viewers.viewer(auth), id, stepId, input == null ? null : input.reason());
    }

    @PostMapping("/offers/{id}/send")
    OfferDetail send(Authentication auth, @PathVariable String id) {
        return offers.send(viewers.tenant(auth), viewers.viewer(auth), id);
    }

    @PostMapping("/offers/{id}/rescind")
    OfferDetail rescind(
            Authentication auth, @PathVariable String id, @RequestBody(required = false) ReasonInput input) {
        return offers.rescind(viewers.tenant(auth), viewers.viewer(auth), id, input == null ? null : input.reason());
    }

    @GetMapping("/offer-templates")
    Page<TemplateView> templates(Authentication auth) {
        return Page.of(offers.templates(viewers.tenant(auth), viewers.viewer(auth)));
    }

    @PutMapping("/offer-templates/{key}")
    TemplateView saveTemplate(Authentication auth, @PathVariable String key, @RequestBody TemplateInput input) {
        return offers.saveTemplate(
                viewers.tenant(auth), viewers.viewer(auth), key, input.name(), input.body(), input.clauseKeys());
    }

    @GetMapping("/offer-clauses")
    Page<ClauseView> clauses(Authentication auth) {
        return Page.of(offers.clauses(viewers.tenant(auth), viewers.viewer(auth)));
    }

    @PutMapping("/offer-clauses/{key}")
    ClauseView saveClause(Authentication auth, @PathVariable String key, @RequestBody ClauseInput input) {
        return offers.saveClause(viewers.tenant(auth), viewers.viewer(auth), key, input.title(), input.body());
    }
}
