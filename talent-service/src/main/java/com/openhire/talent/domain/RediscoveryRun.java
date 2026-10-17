package com.openhire.talent.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** One scored pass of the pool against a job. Explanations are kept per match so a page reload costs no model calls. */
@Document("rediscovery_runs")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_job_recent", def = "{'tenantId': 1, 'jobId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "tenant_recent", def = "{'tenantId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "tenant_candidates", def = "{'tenantId': 1, 'matches.candidateId': 1}")
})
public record RediscoveryRun(
        @Id String id,
        String tenantId,
        String jobId,
        String jobTitle,
        String trigger,
        String triggerEventId,
        Instant createdAt,
        String semanticMode,
        List<String> chips,
        List<Match> matches) {
    public record Match(
            String candidateId,
            String name,
            String title,
            int matchScore,
            double semantic,
            double ruleCoverage,
            double recency,
            double history,
            String whyFitNow,
            String historicalEvidence,
            boolean explained) {}
}
