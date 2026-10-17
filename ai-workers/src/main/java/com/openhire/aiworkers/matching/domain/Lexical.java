package com.openhire.aiworkers.matching.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Stage 2: how much of a criterion's wording (or its must-have skills) appears in the resume, with synonyms. */
public final class Lexical {
    private static final Set<String> STOP = Set.of(
            "and",
            "or",
            "the",
            "of",
            "in",
            "for",
            "with",
            "to",
            "a",
            "an",
            "on",
            "at",
            "by",
            "experience",
            "skills",
            "skill",
            "strong",
            "good",
            "knowledge",
            "ability",
            "understanding",
            "working",
            "years",
            "depth");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9+#.]+");

    private final Taxonomy taxonomy;

    public Lexical(Taxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public double coverage(MatchingRequest.Criterion criterion, List<String> mustHaveSkills, String resumeText) {
        var text = resumeText.toLowerCase(Locale.ROOT);
        var label = criterion.label().toLowerCase(Locale.ROOT);
        var terms = new ArrayList<String>();
        for (String skill : mustHaveSkills) if (contains(label, skill.toLowerCase(Locale.ROOT))) terms.add(skill);
        if (terms.isEmpty()) terms.add(criterion.label());
        return terms.stream()
                .mapToDouble(term -> termCoverage(term, text))
                .average()
                .orElse(0);
    }

    private double termCoverage(String term, String text) {
        if (anyVariant(term, text)) return 1.0;
        var tokens = tokens(term);
        if (tokens.size() < 2) return 0;
        long found = tokens.stream().filter(token -> anyVariant(token, text)).count();
        return (double) found / tokens.size();
    }

    private boolean anyVariant(String term, String text) {
        return taxonomy.variants(term).stream().anyMatch(variant -> contains(text, variant));
    }

    static boolean contains(String text, String phrase) {
        if (phrase.isBlank()) return false;
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(phrase) + "(?![a-z0-9])")
                .matcher(text)
                .find();
    }

    static List<String> tokens(String term) {
        var result = new ArrayList<String>();
        var matcher = TOKEN.matcher(term.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            var token = matcher.group().replaceAll("^\\.+|\\.+$", "");
            if (token.length() >= 2 && !STOP.contains(token) && !result.contains(token)) result.add(token);
        }
        return result;
    }
}
