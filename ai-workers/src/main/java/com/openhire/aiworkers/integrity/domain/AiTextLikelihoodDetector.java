package com.openhire.aiworkers.integrity.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A weak stylometric heuristic (stock phrases and uniform sentence length). It is off by default, its confidence is
 * capped well below certainty and it is only ever a LOW severity hint.
 */
public final class AiTextLikelihoodDetector implements Detector {
    private static final List<String> STOCK = List.of(
            "delve",
            "tapestry",
            "testament to",
            "proven track record",
            "results-driven",
            "passionate about",
            "spearheaded",
            "leverage",
            "synergy",
            "seamlessly",
            "cutting-edge",
            "dynamic environment",
            "fast-paced",
            "in today's");

    private final boolean enabled;

    public AiTextLikelihoodDetector(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String type() {
        return "AI_TEXT_LIKELIHOOD";
    }

    @Override
    public double threshold() {
        return 0.5;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Optional<Finding> detect(DetectionContext context) {
        var text = context.text();
        var sentences = text.split("(?<=[.!?])\\s+");
        int words = text.trim().split("\\s+").length;
        if (sentences.length < 8 || words < 150) return Optional.empty();
        var lower = text.toLowerCase(Locale.ROOT);
        long hits = STOCK.stream().filter(lower::contains).count();
        double density = hits * 100.0 / words;
        double mean = java.util.Arrays.stream(sentences)
                .mapToInt(s -> s.split("\\s+").length)
                .average()
                .orElse(0);
        double variance = java.util.Arrays.stream(sentences)
                .mapToDouble(s -> Math.pow(s.split("\\s+").length - mean, 2))
                .average()
                .orElse(0);
        double variation = mean == 0 ? 1 : Math.sqrt(variance) / mean;
        double uniform = variation < 0.25 ? 1 : variation < 0.4 ? 0.5 : 0;
        double confidence = Math.min(0.7, Math.min(1, density / 2.0) * 0.6 + uniform * 0.4);
        if (confidence < threshold()) return Optional.empty();
        return Optional.of(new Finding(
                "LOW",
                confidence,
                "The writing style resembles generated text (stock phrasing and uniform sentences). "
                        + "This is a weak statistical hint, not evidence."));
    }
}
