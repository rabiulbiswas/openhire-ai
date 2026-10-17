package com.openhire.aiworkers.matching.domain;

import java.time.Instant;
import java.util.List;

/** One assessment version of an application. */
public record Assessment(
        String id,
        String tenantId,
        String applicationId,
        String candidateId,
        String resumeId,
        String jobId,
        int jobVersion,
        String rubricHash,
        String status,
        int score,
        String confidence,
        List<Calibration.CriterionResult> criteria,
        List<String> strengths,
        List<String> gapsToProbe,
        List<String> hardGaps,
        List<String> failedRules,
        Judgement.Provenance provenance,
        String label,
        String commandEventId,
        Instant createdAt) {

    public static final String COMPLETE = "COMPLETE";
    public static final String PARTIAL = "PARTIAL";
    public static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
    public static final String REVIEW_LABEL = "AI recommendation - requires human review";
    public static final String PARTIAL_LABEL = "AI unavailable, partial assessment";
}
