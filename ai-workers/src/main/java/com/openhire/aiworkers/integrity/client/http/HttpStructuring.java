package com.openhire.aiworkers.integrity.client.http;

import com.openhire.aiworkers.integrity.domain.Role;
import com.openhire.aiworkers.integrity.service.port.IntegrityPorts.Structuring;
import com.openhire.aiworkers.shared.client.http.InternalClient;
import com.openhire.support.Access;
import com.openhire.support.Records;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class HttpStructuring implements Structuring {
    private final InternalClient gateway;

    HttpStructuring(Access access, @Value("${openhire.ai.gateway-url}") String gatewayUrl) {
        gateway = new InternalClient(gatewayUrl, access.secret(), 120_000);
    }

    @Override
    public List<Role> roles(String tenantId, String text) {
        var row = gateway.post(tenantId, "/api/v1/internal/ai/structure-resume", Map.of("text", text));
        return Records.documents(row.get("roles")).stream()
                .map(role -> new Role(
                        Records.text(role, "title"),
                        Records.text(role, "company"),
                        Records.text(role, "start"),
                        role.get("end") == null ? null : Records.text(role, "end"),
                        !Boolean.FALSE.equals(role.get("fullTime"))))
                .toList();
    }
}
