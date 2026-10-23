package com.openhire.communication.controller;

import com.openhire.communication.service.CommunicationService;
import com.openhire.communication.service.CommunicationService.TemplateSend;
import com.openhire.support.Access;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service sends on a person's click elsewhere (never routed through the public gateway). */
@RestController
@RequestMapping("/api/v1/internal/messages")
class InternalMessageController {
    private final CommunicationService communication;
    private final Access access;

    InternalMessageController(CommunicationService communication, Access access) {
        this.communication = communication;
        this.access = access;
    }

    record TemplateInput(
            String tenantId, String applicationId, String templateKey, Map<String, String> variables, String actor) {}

    @PostMapping("/template")
    TemplateSend template(@RequestHeader("X-Internal-Service-Secret") String secret, @RequestBody TemplateInput input) {
        access.internal(secret);
        if (blank(input.tenantId()) || blank(input.applicationId()) || blank(input.templateKey()))
            throw new IllegalArgumentException("tenantId, applicationId and templateKey are required");
        return communication.sendTemplate(
                input.tenantId(), input.applicationId(), input.templateKey(), input.variables(), input.actor());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
