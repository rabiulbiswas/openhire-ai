package com.openhire.compliance.domain;

import java.security.SecureRandom;

/** Sortable unique ids: 48-bit time and 80 random bits in Crockford base32. */
public final class Ulid {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String next(String prefix) {
        long time = System.currentTimeMillis();
        var out = new StringBuilder(prefix).append('_');
        for (int i = 9; i >= 0; i--) out.append(ALPHABET[(int) ((time >> (5 * i)) & 31)]);
        for (int i = 0; i < 16; i++) out.append(ALPHABET[RANDOM.nextInt(32)]);
        return out.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
