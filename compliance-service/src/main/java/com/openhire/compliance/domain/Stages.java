package com.openhire.compliance.domain;

import java.util.List;
import java.util.Set;

/** The application pipeline as the application-service defines it, in the order an application moves through it. */
public final class Stages {
    public static final List<String> ORDER =
            List.of("APPLIED", "AI_ASSESSED", "SHORTLISTED", "INTERVIEWING", "DECISION_PENDING", "OFFER", "HIRED");
    public static final Set<String> CLOSED = Set.of("HIRED", "REJECTED", "WITHDRAWN");

    private Stages() {}

    /** Position in the pipeline, or -1 for stages outside it (REJECTED, WITHDRAWN, NEEDS_ATTENTION). */
    public static int rank(String stage) {
        return ORDER.indexOf(stage);
    }

    public static String lifecycle(String stage) {
        return switch (stage == null ? "" : stage) {
            case "HIRED" -> "HIRED";
            case "REJECTED" -> "REJECTED";
            case "WITHDRAWN" -> "WITHDRAWN";
            default -> "ACTIVE";
        };
    }
}
