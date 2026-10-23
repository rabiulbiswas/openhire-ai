package com.openhire.resumeprocessor.client.http;

import com.openhire.resumeprocessor.client.ApplicationLookup;
import com.openhire.support.Access;
import com.openhire.support.ServiceClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Asks application-service, which keeps the candidate id on each application even after erasure. */
@Component
class HttpApplicationLookup implements ApplicationLookup {
    private final ServiceClient applications;

    HttpApplicationLookup(Access access, @Value("${openhire.application-url}") String url) {
        this.applications = new ServiceClient(url, access.secret());
    }

    @Override
    public List<String> applicationIds(String tenantId, String candidateId) {
        var reply = applications.get(
                "/api/v1/internal/candidates/{candidate}/application-ids?tenantId={tenant}", candidateId, tenantId);
        return reply != null && reply.get("applicationIds") instanceof List<?> ids
                ? ids.stream().map(Object::toString).toList()
                : List.of();
    }
}
