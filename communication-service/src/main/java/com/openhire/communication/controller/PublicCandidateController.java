package com.openhire.communication.controller;

import com.openhire.communication.domain.CommunicationTypes.Channel;
import com.openhire.communication.domain.CommunicationTypes.QuietHours;
import com.openhire.communication.dto.CommunicationViews.CandidatePreferencesView;
import com.openhire.communication.dto.CommunicationViews.ChatMessageView;
import com.openhire.communication.dto.CommunicationViews.PortalThreadView;
import com.openhire.communication.service.CommunicationService;
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

/** Candidate portal: access is proven by the application receipt issued at submission. */
@RestController
@RequestMapping("/api/v1/public/applications/{applicationId}")
class PublicCandidateController {
    private final CommunicationService communication;

    PublicCandidateController(CommunicationService communication) {
        this.communication = communication;
    }

    record ReplyInput(String text) {}

    record QuietInput(boolean enabled, String from, String to) {}

    record PreferencesInput(String preferredChannel, QuietInput quietHours, String timezone, boolean marketingOptOut) {}

    @GetMapping("/messages")
    PortalThreadView thread(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return communication.candidateThread(tenantId, applicationId, receipt);
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    ChatMessageView reply(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt,
            @RequestBody ReplyInput input) {
        return communication.candidateReply(tenantId, applicationId, receipt, input.text());
    }

    @GetMapping("/communication-preferences")
    CandidatePreferencesView preferences(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt) {
        return communication.candidatePreferences(tenantId, applicationId, receipt);
    }

    @PutMapping("/communication-preferences")
    CandidatePreferencesView update(
            @PathVariable String applicationId,
            @RequestParam String tenantId,
            @RequestHeader("X-Application-Receipt") String receipt,
            @RequestBody PreferencesInput input) {
        Channel channel;
        try {
            channel = input.preferredChannel() == null ? Channel.EMAIL : Channel.valueOf(input.preferredChannel());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("preferredChannel must be EMAIL, SMS or WHATSAPP");
        }
        var quiet = input.quietHours() == null
                ? null
                : new QuietHours(
                        input.quietHours().enabled(),
                        input.quietHours().from(),
                        input.quietHours().to(),
                        input.timezone());
        return communication.updateCandidatePreferences(
                tenantId, applicationId, receipt, channel, quiet, input.marketingOptOut());
    }
}
