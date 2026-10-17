package com.openhire.approval.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("approval_delegations")
@CompoundIndex(name = "tenant_owner_window", def = "{'tenantId':1,'ownerId':1,'startsAt':1,'endsAt':1}")
public record DelegationPeriod(
        @Id String id,
        String tenantId,
        String ownerId,
        String delegateId,
        Instant startsAt,
        Instant endsAt,
        Instant createdAt) {
    public static DelegationPeriod create(
            String tenantId, String ownerId, String delegateId, Instant startsAt, Instant endsAt) {
        if (endsAt == null || startsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Delegation end must be after its start");
        }
        return new DelegationPeriod(
                UUID.randomUUID().toString(), tenantId, ownerId, delegateId, startsAt, endsAt, Instant.now());
    }
}
