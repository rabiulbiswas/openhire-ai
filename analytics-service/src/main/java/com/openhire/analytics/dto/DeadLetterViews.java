package com.openhire.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class DeadLetterViews {
    private DeadLetterViews() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadLetter(
            String id,
            String originalTopic,
            String consumerGroup,
            String eventType,
            String tenantId,
            String error,
            Integer attempts,
            String firstFailedAt,
            String replayedAt,
            String replayedBy,
            String payloadPreview) {}

    /** The list fields plus the redacted payload and the diagnostics a reviewer needs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeadLetterDetail(
            String id,
            String originalTopic,
            String consumerGroup,
            String eventType,
            String tenantId,
            String error,
            Integer attempts,
            String firstFailedAt,
            String replayedAt,
            String replayedBy,
            String payloadPreview,
            Object payload,
            String deadLetterTopic,
            Integer originalPartition,
            Long originalOffset,
            String exceptionClass,
            Integer replayCount) {}

    public record ReplayResult(String id, String replayedAt, String replayedBy, int replayCount) {}

    public record BatchResult(boolean dryRun, int matched, int replayed) {}
}
