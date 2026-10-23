package com.openhire.offer.controller;

import com.openhire.offer.dto.OfferViews.PublicOfferView;
import com.openhire.offer.dto.OfferViews.ReceiptOfferView;
import com.openhire.offer.service.OfferApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate-facing offer API. The signed link token (header X-Offer-Token) is the credential; the token is kept
 * in the URL fragment by the portal so it never reaches a server log.
 */
@RestController
@RequestMapping("/api/v1/public")
class PublicOfferController {
    private final OfferApplicationService offers;

    PublicOfferController(OfferApplicationService offers) {
        this.offers = offers;
    }

    record AcceptInput(String fullName) {}

    record DeclineInput(String reason) {}

    record NegotiateInput(String message) {}

    @GetMapping("/offers")
    PublicOfferView view(@RequestHeader("X-Offer-Token") String token) {
        return offers.publicView(token);
    }

    @PostMapping("/offers/accept")
    PublicOfferView accept(@RequestHeader("X-Offer-Token") String token, @RequestBody AcceptInput input) {
        return offers.publicAccept(token, input.fullName());
    }

    @PostMapping("/offers/decline")
    PublicOfferView decline(
            @RequestHeader("X-Offer-Token") String token, @RequestBody(required = false) DeclineInput input) {
        return offers.publicDecline(token, input == null ? null : input.reason());
    }

    @PostMapping("/offers/negotiate")
    PublicOfferView negotiate(@RequestHeader("X-Offer-Token") String token, @RequestBody NegotiateInput input) {
        return offers.publicNegotiate(token, input.message());
    }

    @GetMapping("/applications/{applicationId}/offer")
    ReceiptOfferView byReceipt(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return offers.receiptStatus(tenantId, applicationId, receipt);
    }
}
