package com.openhire.aigateway.controller;

import com.openhire.aigateway.dto.AiViews.EmbedRequest;
import com.openhire.aigateway.dto.AiViews.EmbedResponse;
import com.openhire.aigateway.dto.AiViews.ExplainRequest;
import com.openhire.aigateway.dto.AiViews.ExplainResponse;
import com.openhire.aigateway.dto.AiViews.JdRequest;
import com.openhire.aigateway.dto.AiViews.JudgeRequest;
import com.openhire.aigateway.dto.AiViews.JudgeResponse;
import com.openhire.aigateway.dto.AiViews.MessageRequest;
import com.openhire.aigateway.dto.AiViews.MessageResponse;
import com.openhire.aigateway.dto.AiViews.NlRequest;
import com.openhire.aigateway.dto.AiViews.StructureRequest;
import com.openhire.aigateway.service.AiTaskService;
import com.openhire.support.Access;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service AI endpoints. Callers authenticate with the internal secret and name the tenant. */
@RestController
@RequestMapping("/api/v1/internal/ai")
class InternalAiController {
    private final AiTaskService tasks;
    private final Access access;

    InternalAiController(AiTaskService tasks, Access access) {
        this.tasks = tasks;
        this.access = access;
    }

    private String tenant(String secret, String tenant) {
        access.internal(secret);
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("X-Tenant-Id is required");
        return tenant.strip();
    }

    @PostMapping("/embed")
    EmbedResponse embed(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody EmbedRequest request) {
        return tasks.embed(tenant(secret, tenant), request);
    }

    @PostMapping("/judge")
    JudgeResponse judge(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody JudgeRequest request) {
        return tasks.judge(tenant(secret, tenant), request);
    }

    @PostMapping("/structure-resume")
    Map<String, Object> structure(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody StructureRequest request) {
        return tasks.structureResume(tenant(secret, tenant), request);
    }

    @PostMapping("/jd-draft")
    Map<String, Object> jdDraft(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody JdRequest request) {
        return tasks.jdDraft(tenant(secret, tenant), request);
    }

    @PostMapping("/message-draft")
    MessageResponse message(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody MessageRequest request) {
        return tasks.messageDraft(tenant(secret, tenant), request);
    }

    @PostMapping("/nl-filter")
    Map<String, Object> nlFilter(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody NlRequest request) {
        return tasks.nlFilter(tenant(secret, tenant), request);
    }

    @PostMapping("/explain-fit")
    ExplainResponse explain(
            @RequestHeader(value = "X-Internal-Service-Secret", required = false) String secret,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenant,
            @RequestBody ExplainRequest request) {
        return tasks.explainFit(tenant(secret, tenant), request);
    }
}
