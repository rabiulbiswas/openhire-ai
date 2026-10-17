package com.openhire.approval.adapter.out.mongo;

import com.openhire.approval.application.port.DelegationResolver;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class MongoDelegationResolver implements DelegationResolver {
    private final DelegationPeriodRepository periods;

    public MongoDelegationResolver(DelegationPeriodRepository periods) {
        this.periods = periods;
    }

    @Override
    public String resolve(String tenantId, String ownerId, Instant at) {
        if (ownerId == null || "me".equalsIgnoreCase(ownerId)) return ownerId;
        return periods.findFirstByTenantIdAndOwnerIdAndStartsAtLessThanEqualAndEndsAtGreaterThanEqualOrderByStartsAtDesc(
                        tenantId, ownerId, at, at)
                .map(value -> value.delegateId())
                .orElse(ownerId);
    }
}
