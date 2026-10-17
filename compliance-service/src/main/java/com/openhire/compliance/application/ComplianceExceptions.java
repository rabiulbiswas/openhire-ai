package com.openhire.compliance.application;

public final class ComplianceExceptions {
    private ComplianceExceptions() {}

    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    public static class Gone extends RuntimeException {
        public Gone(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
