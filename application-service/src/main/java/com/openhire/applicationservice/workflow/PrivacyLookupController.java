package com.openhire.applicationservice.workflow;

import com.openhire.support.Access;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets services that store only application ids find a candidate's applications when they answer a privacy request. */
@RestController
@RequestMapping("/api/v1/internal/candidates")
class PrivacyLookupController {
    private final PrivacyData data;
    private final Access access;

    PrivacyLookupController(PrivacyData data, Access access) {
        this.data = data;
        this.access = access;
    }

    @GetMapping("/{candidateId}/application-ids")
    Map<String, List<String>> applicationIds(
            @RequestHeader("X-Internal-Service-Secret") String secret,
            @PathVariable String candidateId,
            @RequestParam String tenantId) {
        access.internal(secret);
        return Map.of("applicationIds", data.applicationIds(tenantId, candidateId));
    }
}
