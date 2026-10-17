package com.openhire.aigateway.application;

import java.util.List;

public final class AiExceptions {
    private AiExceptions() {}

    /** The model is unreachable, the circuit is open, the gateway is saturated or the output could not be made valid. */
    public static class AiUnavailable extends RuntimeException {
        public AiUnavailable(String message) {
            super(message);
        }

        public AiUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The tenant's monthly budget for non-local providers is used up. */
    public static class BudgetExhausted extends RuntimeException {
        public BudgetExhausted(String message) {
            super(message);
        }
    }

    public static class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    /** Promotion refused because the latest evaluation did not pass every gate. */
    public static class PromotionBlocked extends RuntimeException {
        private final List<String> failedGates;

        public PromotionBlocked(String message, List<String> failedGates) {
            super(message);
            this.failedGates = List.copyOf(failedGates);
        }

        public List<String> failedGates() {
            return failedGates;
        }
    }

    public static class EvaluationBusy extends RuntimeException {
        public EvaluationBusy(String message) {
            super(message);
        }
    }

    public record FieldError(String field, String message) {}

    /** A 400 whose detail is readable and which names the offending field when one is at fault. */
    public static class ValidationFailed extends IllegalArgumentException {
        private final List<FieldError> errors;

        public ValidationFailed(String message, String field) {
            super(message);
            this.errors = field == null ? List.of() : List.of(new FieldError(field, message));
        }

        public List<FieldError> errors() {
            return errors;
        }
    }
}
