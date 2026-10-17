package com.openhire.aigateway.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Text normalisation used by grounding checks and the token budget. */
public final class Text {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern EDGE = Pattern.compile("^[\\s\"'`.\\u2026]+|[\\s\"'`\\u2026]+$");

    private Text() {}

    /** Case-, whitespace-, quote- and dash-insensitive form of a string. */
    public static String normalize(String text) {
        if (text == null) return "";
        var value = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('‘', '\'')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')
                .replace('—', '-')
                .replace('•', ' ')
                .toLowerCase(java.util.Locale.ROOT);
        return SPACE.matcher(value).replaceAll(" ").strip();
    }

    /** A quote as the model wrote it, without wrapping quote marks or trailing ellipses. */
    public static String normalizeQuote(String quote) {
        return EDGE.matcher(normalize(quote)).replaceAll("");
    }

    public static boolean contains(String haystack, String needle) {
        var n = normalizeQuote(needle);
        return n.length() >= 3 && normalize(haystack).contains(n);
    }

    /** Rough token estimate that errs on the high side so the budget check is conservative. */
    public static int estimateTokens(String text) {
        return text == null ? 0 : (text.length() + 2) / 3;
    }
}
