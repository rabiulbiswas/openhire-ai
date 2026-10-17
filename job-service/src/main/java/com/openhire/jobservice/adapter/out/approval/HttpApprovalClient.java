package com.openhire.jobservice.adapter.out.approval;

import com.openhire.jobservice.application.port.ApprovalClient;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpApprovalClient implements ApprovalClient {
    private final RestClient client;
    private final String secret;

    public HttpApprovalClient(
            RestClient.Builder builder,
            @Value("${openhire.approval.base-url:http://localhost:8083}") String baseUrl,
            @Value("${openhire.internal-secret:local-development-secret}") String secret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.secret = secret;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String requestJobApproval(String tenantId, String actorId, String jobId, String title, int version) {
        var response = client.post()
                .uri("/api/v1/internal/approvals")
                .header("X-Tenant-Id", tenantId)
                .header("X-Actor-Id", actorId)
                .header("X-Internal-Service-Secret", secret)
                .body(Map.of(
                        "type",
                        "JOB_NEW",
                        "subjectId",
                        jobId,
                        "title",
                        title,
                        "steps",
                        List.of(Map.of(
                                "id", "job-" + version + "-manager",
                                "approver", "me",
                                "role", "Hiring manager"))))
                .retrieve()
                .body(Map.class);
        return response == null ? null : String.valueOf(response.get("id"));
    }
}
