package com.openhire.communication.adapter.in.web;

import com.openhire.communication.application.CommunicationService;
import com.openhire.communication.application.CommunicationViews.ChatMessageView;
import com.openhire.communication.application.CommunicationViews.ConversationSummaryView;
import com.openhire.communication.application.CommunicationViews.ConversationView;
import com.openhire.communication.application.CommunicationViews.Page;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController {
    private final CommunicationService communication;
    private final StaffViewers viewers;

    ConversationController(CommunicationService communication, StaffViewers viewers) {
        this.communication = communication;
        this.viewers = viewers;
    }

    record StartInput(String applicationId) {}

    record SendInput(String text, String channel) {}

    @GetMapping
    Page<ConversationSummaryView> list(Authentication auth) {
        viewers.recruiter(auth);
        return communication.conversations(viewers.tenant(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationView start(Authentication auth, @RequestBody StartInput input) {
        viewers.recruiter(auth);
        if (input.applicationId() == null || input.applicationId().isBlank())
            throw new IllegalArgumentException("applicationId is required");
        return communication.startConversation(viewers.tenant(auth), input.applicationId());
    }

    @GetMapping("/{id}")
    ConversationView get(Authentication auth, @PathVariable String id) {
        viewers.recruiter(auth);
        return communication.conversation(viewers.tenant(auth), id);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    ChatMessageView send(Authentication auth, @PathVariable String id, @RequestBody SendInput input) {
        var viewer = viewers.recruiter(auth);
        Channel channel = null;
        if (input.channel() != null && !input.channel().isBlank()) {
            try {
                channel = Channel.valueOf(input.channel());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("channel must be EMAIL, SMS or WHATSAPP");
            }
        }
        return communication.send(viewers.tenant(auth), id, input.text(), channel, viewer);
    }

    @PostMapping("/{id}/ai-draft")
    Map<String, String> aiDraft(Authentication auth, @PathVariable String id) {
        viewers.recruiter(auth);
        return Map.of("text", communication.aiDraft(viewers.tenant(auth), id));
    }
}
