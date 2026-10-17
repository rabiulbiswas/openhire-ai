package com.openhire.communication.adapter.in.web;

import com.openhire.communication.application.CommunicationService;
import com.openhire.communication.application.CommunicationViews.Page;
import com.openhire.communication.application.CommunicationViews.PreviewView;
import com.openhire.communication.application.CommunicationViews.TemplateView;
import com.openhire.communication.domain.CommunicationTypes.Channel;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/message-templates")
class TemplateController {
    private final CommunicationService communication;
    private final StaffViewers viewers;

    TemplateController(CommunicationService communication, StaffViewers viewers) {
        this.communication = communication;
        this.viewers = viewers;
    }

    record TemplateInput(List<String> channels, String subject, String body) {}

    @GetMapping
    Page<TemplateView> list(Authentication auth) {
        viewers.recruiter(auth);
        return Page.of(communication.templates(viewers.tenant(auth)));
    }

    @PutMapping("/{key}")
    TemplateView update(Authentication auth, @PathVariable String key, @RequestBody TemplateInput input) {
        var viewer = viewers.recruiter(auth);
        var channels = input.channels() == null
                ? List.<Channel>of()
                : input.channels().stream()
                        .map(value -> {
                            try {
                                return Channel.valueOf(value);
                            } catch (IllegalArgumentException invalid) {
                                throw new IllegalArgumentException("Unknown channel " + value);
                            }
                        })
                        .toList();
        return communication.updateTemplate(viewers.tenant(auth), key, channels, input.subject(), input.body(), viewer);
    }

    @PostMapping("/{key}/preview")
    PreviewView preview(
            Authentication auth, @PathVariable String key, @RequestBody(required = false) Map<String, String> sample) {
        viewers.recruiter(auth);
        return communication.preview(viewers.tenant(auth), key, sample);
    }
}
