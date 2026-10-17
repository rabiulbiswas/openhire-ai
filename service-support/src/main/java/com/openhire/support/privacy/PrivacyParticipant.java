package com.openhire.support.privacy;

import java.util.Map;

/**
 * A service that holds personal data about candidates implements this once. The framework delivers export and
 * erasure requests from compliance-service and acknowledges the result; the service only decides what it holds.
 *
 * <p>Both operations must be idempotent: a request can be redelivered. {@code erase} must also leave a tombstone
 * ({@link Tombstones#mark}) and make the service's own event consumers skip an erased candidate, so a late event
 * cannot recreate what was erased.
 */
public interface PrivacyParticipant {
    /** The service's name, as registered with compliance-service (its spring.application.name). */
    String service();

    /** The candidate's data held here, as JSON-safe values. No secrets, hashes or other people's data. */
    Map<String, Object> export(String tenantId, String candidateId);

    Outcome erase(String tenantId, String requestId, String candidateId);

    enum Status {
        /** Personal data existed and was deleted or irreversibly anonymised. */
        ERASED,
        /** This service held nothing about the candidate. */
        NOTHING_HELD,
        /** Data is kept on a lawful basis (for example an employment record) with the reason given. */
        RETAINED_LEGAL_BASIS
    }

    record Outcome(Status status, Map<String, Integer> counts, String note) {
        public Outcome {
            counts = counts == null ? Map.of() : Map.copyOf(counts);
        }

        public static Outcome erased(Map<String, Integer> counts) {
            return new Outcome(
                    counts.values().stream().mapToInt(Integer::intValue).sum() == 0
                            ? Status.NOTHING_HELD
                            : Status.ERASED,
                    counts,
                    null);
        }

        public static Outcome nothingHeld() {
            return new Outcome(Status.NOTHING_HELD, Map.of(), null);
        }

        public static Outcome retained(String note, Map<String, Integer> counts) {
            return new Outcome(Status.RETAINED_LEGAL_BASIS, counts, note);
        }
    }
}
