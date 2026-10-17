package com.openhire.aiworkers.integrity.domain;

import com.openhire.aiworkers.matching.domain.Hashes;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Salted hashes of contact details and name tokens, so the duplicate index never stores personal data. */
public final class Identities {
    private static final Pattern WORD = Pattern.compile("\\p{L}{2,}");

    private Identities() {}

    public static String email(String tenantId, String email) {
        var value = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "" : Hashes.sha256(tenantId, "email", value);
    }

    public static String phone(String tenantId, String phone) {
        var digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < 7) return "";
        return Hashes.sha256(tenantId, "phone", digits.substring(Math.max(0, digits.length() - 10)));
    }

    public static Set<String> nameTokens(String tenantId, String name) {
        var tokens = new TreeSet<String>();
        if (name == null) return tokens;
        var matcher = WORD.matcher(name.toLowerCase(Locale.ROOT));
        while (matcher.find()) tokens.add(Hashes.sha256(tenantId, "name", matcher.group()));
        return tokens;
    }

    /** Names are the same person's when they share at least half of their tokens ("Priya Das", "Priya K Das"). */
    public static boolean sameName(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        long shared = a.stream().filter(b::contains).count();
        return (double) shared / (a.size() + b.size() - shared) >= 0.5;
    }
}
