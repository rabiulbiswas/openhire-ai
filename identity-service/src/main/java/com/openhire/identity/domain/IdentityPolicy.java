package com.openhire.identity.domain;

import static com.openhire.identity.domain.IdentityModels.InvitationStatus.PENDING;

import com.openhire.identity.domain.IdentityModels.Invitation;
import java.time.Clock;
import java.util.Locale;
import java.util.regex.Pattern;

public final class IdentityPolicy {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private IdentityPolicy() {}

    public static String normalizeEmail(String email) {
        var value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        return value;
    }

    public static String validateSlug(String slug) {
        var value = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(value).matches() || value.length() > 63) {
            throw new IllegalArgumentException("Slug must contain lowercase letters, numbers, and hyphens");
        }
        if (java.util.Set.of("admin", "auth", "api", "www", "mail", "static", "assets", "support", "localhost")
                .contains(value)) {
            throw new IllegalArgumentException("This slug is reserved for platform infrastructure");
        }
        return value;
    }

    public static void requireAcceptable(Invitation invitation, Clock clock) {
        if (invitation.status() != PENDING || !invitation.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Invitation is invalid or expired");
        }
    }
}
