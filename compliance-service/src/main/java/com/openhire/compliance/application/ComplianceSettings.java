package com.openhire.compliance.application;

import java.util.List;

public record ComplianceSettings(
        List<String> participants,
        int minGroupSize,
        double adverseImpactThreshold,
        int erasureSlaDays,
        int stuckAfterHours,
        int exportTtlDays,
        int retentionBatch) {

    public static ComplianceSettings defaults(List<String> participants) {
        return new ComplianceSettings(participants, 5, 0.8, 30, 24, 7, 50);
    }
}
