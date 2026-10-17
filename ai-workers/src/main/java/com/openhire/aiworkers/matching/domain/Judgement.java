package com.openhire.aiworkers.matching.domain;

import java.util.List;

/** One gateway {@code judge} answer, before and after local grounding verification. */
public record Judgement(
        String criterionId,
        double score,
        List<Evidence> evidence,
        List<String> strengths,
        List<String> gaps,
        String rationale,
        boolean insufficientEvidence,
        boolean gatewayGrounded,
        Provenance provenance) {

    public record Evidence(String chunkId, String quote) {}

    public record Provenance(String model, String promptKey, String promptVersion, String traceId) {}

    private static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Quotes that really occur (whitespace and case normalised) in the chunk they cite among the chunks shown. */
    public List<String> verifiedQuotes(java.util.Map<String, String> shownChunks) {
        if (insufficientEvidence || !gatewayGrounded) return List.of();
        return evidence.stream()
                .filter(e -> !e.quote().isBlank() && shownChunks.containsKey(e.chunkId()))
                .filter(e -> normalize(shownChunks.get(e.chunkId())).contains(normalize(e.quote())))
                .map(Evidence::quote)
                .distinct()
                .toList();
    }

    /**
     * True when the model cited evidence that grounding discarded (the gateway rejected it, or a quote is not in the
     * chunk it cites). An honest "no evidence" answer (no quotes at all) is not a discard.
     */
    public boolean discardedQuotes(java.util.Map<String, String> shownChunks) {
        var quotes = evidence.stream().filter(e -> !e.quote().isBlank()).toList();
        if (quotes.isEmpty()) return false;
        if (insufficientEvidence || !gatewayGrounded) return true;
        return quotes.size() != verifiedQuotes(shownChunks).size()
                && quotes.stream()
                        .anyMatch(e -> !shownChunks.containsKey(e.chunkId())
                                || !normalize(shownChunks.get(e.chunkId())).contains(normalize(e.quote())));
    }
}
