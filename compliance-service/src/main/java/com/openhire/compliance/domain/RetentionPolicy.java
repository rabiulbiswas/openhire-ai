package com.openhire.compliance.domain;

import java.util.List;

public record RetentionPolicy(
        String key, String label, String description, int retentionMonths, String action, boolean enabled) {

    public static final String NON_HIRED = "candidate.non-hired";
    public static final String WITHDRAWN = "candidate.withdrawn";
    public static final String AUDIT = "audit.events";
    public static final String AI_LOGS = "ai.request-logs";

    public static final List<RetentionPolicy> DEFAULTS = List.of(
            new RetentionPolicy(
                    NON_HIRED,
                    "Candidates who were not hired",
                    "Erase a candidate's data this long after their last activity, unless they were hired.",
                    24,
                    "ERASE",
                    true),
            new RetentionPolicy(
                    WITHDRAWN,
                    "Candidates who withdrew",
                    "Erase a candidate's data this long after they withdrew every application.",
                    6,
                    "ERASE",
                    true),
            new RetentionPolicy(
                    AUDIT,
                    "Audit records",
                    "Archive audit records after this long. Never deleted by the retention run.",
                    84,
                    "ARCHIVE",
                    true),
            new RetentionPolicy(
                    AI_LOGS,
                    "AI request logs",
                    "Remove AI request logs after this long. Applied by the AI services.",
                    12,
                    "ERASE",
                    true));

    public static RetentionPolicy defaultFor(String key) {
        return DEFAULTS.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    public int minimumMonths() {
        return key.equals(AUDIT) ? 12 : 1;
    }
}
