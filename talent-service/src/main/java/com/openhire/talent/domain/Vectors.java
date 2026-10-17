package com.openhire.talent.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class Vectors {
    private static final Pattern WORD = Pattern.compile("[a-z0-9+#.]{3,}");
    private static final Set<String> STOP = Set.of(
            "the",
            "and",
            "for",
            "with",
            "you",
            "our",
            "are",
            "will",
            "have",
            "has",
            "from",
            "that",
            "this",
            "your",
            "who",
            "role",
            "team",
            "work",
            "years",
            "year",
            "experience");

    private Vectors() {}

    /** Cosine similarity; vectors of different length or zero norm score 0. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Token-set cosine in 0..1: the stand-in for semantic similarity while the embedding model is unavailable. */
    public static double lexical(String a, String b) {
        var left = tokens(a);
        var right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        var common = new HashSet<>(left);
        common.retainAll(right);
        return common.size() / Math.sqrt((double) left.size() * right.size());
    }

    public static Set<String> tokens(String text) {
        var result = new HashSet<String>();
        if (text == null) return result;
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) if (!STOP.contains(matcher.group())) result.add(matcher.group());
        return result;
    }
}
