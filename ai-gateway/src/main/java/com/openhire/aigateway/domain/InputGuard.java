package com.openhire.aigateway.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prepares untrusted text for a model: removes personal and protected-attribute details the task does not need,
 * flags prompt-injection attempts (flagged, never silently dropped) and keeps the delimiters of data blocks intact.
 */
public final class InputGuard {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w])\\+?\\d[\\d\\s().-]{7,}\\d(?![\\w])");
    private static final Pattern LABELLED = Pattern.compile(
            "(?im)\\b(date of birth|d\\.o\\.b\\.?|dob|born|age|gender|sex|marital status|religion|caste|nationality"
                    + "|father'?s name|mother'?s name|spouse'?s? name)\\s*[:\\-\\u2013]\\s*[^\\n,;|]{1,60}");
    private static final Pattern BORN = Pattern.compile("(?i)\\bborn\\s+(?:on|in)\\s+[^\\n.,;]{1,30}");
    private static final Pattern AGE_YEARS = Pattern.compile("(?i)\\b\\d{2}\\s*[- ]?(?:years?|yrs?)[\\s-]*old\\b");
    private static final Pattern AGE_OF = Pattern.compile("(?i)\\bage[d]?\\s*(?:of\\s*)?\\d{2}\\b|\\b\\d{2}\\s*y/o\\b");
    private static final Pattern STATUS = Pattern.compile(
            "(?i)\\b(?:i am|is|am)\\s+(?:a\\s+)?(?:happily\\s+)?(?:married|unmarried|single|divorced|widowed)\\b[^\\n.]*");
    private static final Pattern HONORIFIC = Pattern.compile("\\b(?:Mr|Mrs|Ms|Miss|Mx|Shri|Smt)\\.?(?=\\s+[A-Z])\\s*");
    private static final Pattern PRONOUN_TAG =
            Pattern.compile("(?i)\\(\\s*(?:he|she|they)\\s*/\\s*(?:him|her|them)\\s*\\)");
    private static final Pattern DELIMITERS =
            Pattern.compile("(?i)<(/?)(resume_excerpts|resume|thread|notes|query|job|brief|candidate_history|"
                    + "current_resume|data)(?=[\\s>])");

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile(
                    "(?i)\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,40}\\b(?:previous|prior|above|earlier|all|any|the|your)\\b[^.\\n]{0,30}\\b(?:instructions?|prompts?|guidelines?|messages?)\\b"),
            Pattern.compile(
                    "(?i)\\b(?:give|assign|award|rate|score|mark|set)\\s+(?:this|the|me|my|him|her|them|it)\\b[^.\\n]{0,40}\\b(?:5|five|10|ten|max(?:imum)?|highest|perfect|full)\\b"),
            Pattern.compile("(?i)\\b(?:highest|maximum|perfect|top)\\s+(?:possible\\s+)?(?:score|rating|grade)\\b"),
            Pattern.compile("(?i)\\byou\\s+(?:are|must)\\s+now\\b[^.\\n]{0,60}"),
            Pattern.compile("(?i)\\b(?:system|developer)\\s+(?:prompt|message|instruction)s?\\b"),
            Pattern.compile("(?i)\\bnew\\s+instructions?\\b"),
            Pattern.compile(
                    "(?i)\\bact\\s+as\\s+(?:a|an|the)?\\s*(?:hiring|recruiter|evaluator|assistant|ai|model|system|judge|interviewer)\\b[^.\\n]{0,40}"),
            Pattern.compile("(?i)\\b(?:recommend|hire|shortlist|select)\\s+(?:this|the)\\s+candidate\\b[^.\\n]{0,40}"),
            Pattern.compile("(?i)<\\|?(?:im_start|im_end|system|assistant)\\|?>|\\[/?INST]"),
            Pattern.compile("(?im)^\\s*(?:#{1,3}\\s*)?(?:system|assistant)\\s*:"));

    private InputGuard() {}

    /** Redaction result: cleaned text and how many details were removed. */
    public record Redacted(String text, int count) {}

    /** Removes contact details, protected-attribute statements and, when supplied, the candidate's name. */
    public static Redacted redact(String text, String candidateName) {
        if (text == null || text.isEmpty()) return new Redacted("", 0);
        int[] count = {0};
        var value = text;
        value = replace(value, EMAIL, "[EMAIL]", count);
        value = PHONE.matcher(value).replaceAll(match -> {
            long digits = match.group().chars().filter(Character::isDigit).count();
            boolean years = java.util.Arrays.stream(match.group().strip().split("[\\s-]+"))
                    .allMatch(part -> part.matches("(?:19|20)\\d{2}"));
            if (digits < 10 || years) return java.util.regex.Matcher.quoteReplacement(match.group());
            count[0]++;
            return "[PHONE]";
        });
        value = LABELLED.matcher(value).replaceAll(match -> {
            count[0]++;
            return java.util.regex.Matcher.quoteReplacement(match.group(1) + ": [REDACTED]");
        });
        value = replace(value, BORN, "[REDACTED]", count);
        value = replace(value, AGE_YEARS, "[REDACTED]", count);
        value = replace(value, AGE_OF, "[REDACTED]", count);
        value = replace(value, STATUS, "[REDACTED]", count);
        value = replace(value, PRONOUN_TAG, "", count);
        value = replace(value, HONORIFIC, "", count);
        if (candidateName != null && !candidateName.isBlank())
            for (var token : candidateName.strip().split("\\s+"))
                if (token.length() >= 2)
                    value = replace(
                            value,
                            Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "(?![\\p{L}\\p{N}])"),
                            "[CANDIDATE]",
                            count);
        return new Redacted(value, count[0]);
    }

    private static String replace(String text, Pattern pattern, String replacement, int[] count) {
        var matcher = pattern.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            count[0]++;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Instruction-like snippets found in untrusted text. */
    public static List<String> injections(String text) {
        var found = new ArrayList<String>();
        if (text == null) return found;
        for (var pattern : INJECTION) {
            var matcher = pattern.matcher(text);
            while (matcher.find()) found.add(matcher.group().strip());
        }
        return found;
    }

    public static boolean suspicious(String text) {
        return !injections(text).isEmpty();
    }

    /** True when a quote is itself instruction-like and so can never count as evidence. */
    public static boolean instructionLike(String quote) {
        return suspicious(quote);
    }

    /** Stops untrusted text from closing or opening one of the prompt's data blocks. */
    public static String neutralizeDelimiters(String text) {
        if (text == null) return "";
        return DELIMITERS
                .matcher(text)
                .replaceAll(m -> "<​" + m.group(1) + m.group(2).toLowerCase(Locale.ROOT));
    }
}
