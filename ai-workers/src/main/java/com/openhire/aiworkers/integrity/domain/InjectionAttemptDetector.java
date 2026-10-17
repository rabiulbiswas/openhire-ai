package com.openhire.aiworkers.integrity.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Instruction-like text aimed at a model. Evidence names the patterns only; the resume text is not repeated. */
public final class InjectionAttemptDetector implements Detector {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(
                "ignore-instructions",
                Pattern.compile(
                        "(ignore|disregard|forget|override)\\s+(all\\s+|any\\s+|the\\s+|your\\s+)?(previous|prior|above|earlier|preceding)\\s+(instructions?|prompts?|rules?|context)",
                        FLAGS));
        PATTERNS.put("role-override", Pattern.compile("you\\s+are\\s+now\\b|pretend\\s+(to\\s+be|you\\s+are)", FLAGS));
        PATTERNS.put(
                "score-manipulation",
                Pattern.compile(
                        "(give|assign|award)\\s+(me|this\\s+(candidate|resume|applicant)|the\\s+candidate)\\s+(a\\s+)?(score|rating|grade)\\s+of\\s+\\d+"
                                + "|(rate|rank|score|recommend)\\s+(this\\s+)?(candidate|resume|applicant|me)\\s+(as\\s+)?(highly|the\\s+best|perfect|excellent|10|100)"
                                + "|this\\s+candidate\\s+(is|must\\s+be)\\s+(the\\s+)?(best|perfect|ideal)\\b",
                        FLAGS));
        PATTERNS.put(
                "prompt-extraction",
                Pattern.compile(
                        "(reveal|print|show|output|repeat)\\s+(your\\s+)?(system\\s+)?(prompt|instructions)", FLAGS));
        PATTERNS.put(
                "chat-delimiters",
                Pattern.compile(
                        "<\\|?(im_start|im_end|system|assistant)\\|?>|\\[/?INST\\]|###\\s*(system|instructions?)\\b",
                        FLAGS));
        PATTERNS.put(
                "concealment", Pattern.compile("do\\s+not\\s+(mention|tell|reveal|flag)\\s+(this|that|these)", FLAGS));
        PATTERNS.put("ai-persona", Pattern.compile("as\\s+an\\s+ai\\s+(language\\s+)?model", FLAGS));
    }

    @Override
    public String type() {
        return "INJECTION_ATTEMPT";
    }

    @Override
    public double threshold() {
        return 0.6;
    }

    @Override
    public Optional<Finding> detect(DetectionContext context) {
        var hits = PATTERNS.entrySet().stream()
                .filter(e -> e.getValue().matcher(context.text()).find())
                .map(Map.Entry::getKey)
                .toList();
        if (hits.isEmpty()) return Optional.empty();
        var severity = hits.size() >= 2 || hits.contains("score-manipulation") ? "HIGH" : "MEDIUM";
        return Optional.of(new Finding(
                severity,
                Math.min(0.95, 0.6 + 0.15 * hits.size()),
                "The resume text contains instruction-like wording aimed at an AI model (" + String.join(", ", hits)
                        + "). It was treated as data and did not change any score."));
    }
}
