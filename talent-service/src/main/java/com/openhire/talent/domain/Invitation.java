package com.openhire.talent.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** A person's click that invited a pool candidate to apply. It drives the cooldown and the re-engagement metrics. */
@Document("talent_invitations")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_candidate_recent", def = "{'tenantId': 1, 'candidateId': 1, 'invitedAt': -1}"),
    @CompoundIndex(name = "tenant_recent", def = "{'tenantId': 1, 'invitedAt': -1}")
})
public record Invitation(
        @Id String id,
        String tenantId,
        String candidateId,
        String jobId,
        String jobTitle,
        String applicationId,
        String actorId,
        Instant invitedAt) {}
