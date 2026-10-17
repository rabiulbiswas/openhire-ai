package com.openhire.aiworkers.matching.domain;

import com.openhire.aiworkers.matching.domain.MatchingRequest.Criterion;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Stage 5: blends the judge with lexical coverage, flags hard gaps and derives the confidence band. */
public final class Calibration {
    public static final double MUST_FLOOR = 0.4;
    public static final double HIGH = 0.75;
    public static final double MEDIUM = 0.5;
    private static final double STRONG = 0.7;

    private Calibration() {}

    public record Input(Criterion criterion, double lexical, double llmScore, boolean grounded, List<String> quotes) {}

    public record CriterionResult(
            String id,
            String label,
            String requirement,
            double weight,
            int score,
            List<String> evidence,
            boolean grounded) {}

    public record Result(
            int score,
            String confidence,
            double confidenceValue,
            List<CriterionResult> criteria,
            List<String> hardGaps,
            List<String> strengths,
            List<String> gapsToProbe,
            boolean anyGrounded) {}

    /** {@code s = grounded ? 0.7 * llm/5 + 0.3 * lexical : 0.5 * lexical}; weights are normalised to sum to one. */
    public static double criterionScore(boolean grounded, double llmScore, double lexical) {
        double llm = Math.max(0, Math.min(5, llmScore));
        return grounded ? 0.7 * (llm / 5) + 0.3 * lexical : 0.5 * lexical;
    }

    public static Result calibrate(List<Input> inputs, double completeness) {
        var scores = new ArrayList<Double>();
        for (var input : inputs) scores.add(criterionScore(input.grounded(), input.llmScore(), input.lexical()));
        double groundedCount = inputs.stream().filter(Input::grounded).count();
        double withEvidence = inputs.stream().filter(i -> !i.quotes().isEmpty()).count();
        double agreement = groundedCount == 0
                ? 0
                : 1
                        - inputs.stream()
                                        .filter(Input::grounded)
                                        .mapToDouble(i -> Math.abs(i.llmScore() / 5 - i.lexical()))
                                        .sum()
                                / groundedCount;
        int size = Math.max(1, inputs.size());
        double confidence = 0.35 * (withEvidence / size)
                + 0.30 * (groundedCount / size)
                + 0.15 * completeness
                + 0.20 * Math.max(0, agreement);
        return assemble(inputs, scores, groundedCount > 0, confidence);
    }

    /** Stages 1 and 2 only: coverage stands in for the score and the band is always LOW. */
    public static Result lexicalOnly(List<Criterion> rubric, List<Double> coverage) {
        var inputs = new ArrayList<Input>();
        for (int i = 0; i < rubric.size(); i++)
            inputs.add(new Input(rubric.get(i), coverage.get(i), 0, false, List.of()));
        var result = assemble(inputs, coverage, false, 0);
        return new Result(
                result.score(),
                "LOW",
                0,
                result.criteria(),
                result.hardGaps(),
                result.strengths(),
                result.gapsToProbe(),
                false);
    }

    public static String band(double confidence) {
        return confidence >= HIGH ? "HIGH" : confidence >= MEDIUM ? "MEDIUM" : "LOW";
    }

    /** Profile completeness in 0..1 from resume length and the presence of experience, skills and education. */
    public static double completeness(int words, Set<String> sections) {
        double length = Math.min(1, words / 250.0) * 0.5;
        int present = 0;
        for (String section : List.of("EXPERIENCE", "SKILLS", "EDUCATION")) if (sections.contains(section)) present++;
        return length + present / 3.0 * 0.5;
    }

    private static Result assemble(List<Input> inputs, List<Double> scores, boolean anyGrounded, double confidence) {
        double totalWeight =
                inputs.stream().mapToDouble(i -> i.criterion().weight()).sum();
        double blended = 0;
        var criteria = new ArrayList<CriterionResult>();
        var hardGaps = new ArrayList<String>();
        var strengths = new ArrayList<String>();
        var gaps = new ArrayList<String>();
        for (int i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            var criterion = input.criterion();
            double s = scores.get(i);
            blended += (totalWeight == 0 ? 0 : criterion.weight() / totalWeight) * s;
            criteria.add(new CriterionResult(
                    criterion.id(),
                    criterion.label(),
                    criterion.requirement(),
                    criterion.weight(),
                    (int) Math.round(100 * s),
                    input.quotes(),
                    input.grounded()));
            if (criterion.must() && s < MUST_FLOOR) hardGaps.add(criterion.id());
            if (input.grounded() && s >= STRONG) strengths.add(criterion.label());
            else if (s < MUST_FLOOR || (anyGrounded && !input.grounded())) gaps.add(criterion.label());
        }
        return new Result(
                (int) Math.round(100 * blended),
                band(confidence),
                confidence,
                criteria,
                hardGaps,
                strengths,
                gaps,
                anyGrounded);
    }
}
