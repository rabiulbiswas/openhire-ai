package com.openhire.platform.security;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Live, tenant-scoped membership checks. Never infer assignment from a token role alone. */
@Component
public class TenantStaffDirectory {
    public record Member(String id, String email, String displayName, Set<String> roles) {}

    private final RestClient client;
    private final String secret;

    public TenantStaffDirectory(
            @Value("${IDENTITY_SERVICE_URL:http://identity-service:8081}") String url,
            @Value("${openhire.internal-secret:${openhire.internal.secret:local-development-secret}}") String secret) {
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requests.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(url).requestFactory(requests).build();
        this.secret = secret;
    }

    public List<Member> members(String tenant) {
        var rows = client.get()
                .uri("/api/v1/internal/approval-members?tenantId={tenant}", tenant)
                .header("X-Internal-Service-Secret", secret)
                .retrieve()
                .body(Member[].class);
        return rows == null ? List.of() : List.of(rows);
    }

    public Member require(String tenant, String id, String role) {
        var normalized = role.trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_');
        var required = "FINANCE".equals(normalized) ? "ORG_ADMIN" : normalized;
        return members(tenant).stream()
                .filter(m -> m.id().equals(id) && m.roles().contains(required))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Select an active " + required + " from this organisation"));
    }
}
