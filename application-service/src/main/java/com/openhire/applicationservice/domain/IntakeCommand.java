package com.openhire.applicationservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Normalized intake. Candidate identity resolution is a separate service concern. */
public record IntakeCommand(
        String jobId,
        String name,
        String email,
        String owner,
        boolean processingConsent,
        String consentVersion,
        String externalReference) {
    public IntakeCommand {
        jobId = required(jobId, "jobId", 100);
        name = required(name, "name", 200);
        email = required(email, "email", 254).toLowerCase(Locale.ROOT);
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        owner = optional(owner, 100);
        consentVersion = required(consentVersion, "consentVersion", 100);
        externalReference = optional(externalReference, 200);
        if (!processingConsent) throw new IllegalArgumentException("Processing consent must be recorded");
    }

    public String fingerprint() {
        return digest(
                jobId, name, email, owner, Boolean.toString(processingConsent), consentVersion, externalReference);
    }

    /** Length prefixes prevent ambiguous tenant/key or payload concatenations. */
    public static String digest(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(
                        java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    public static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters");
        }
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return "";
        return required(value, "Optional value", max);
    }
}
