package com.openhire.interview.domain;

import com.openhire.interview.domain.InterviewTypes.Rating;
import com.openhire.interview.domain.InterviewTypes.RoundStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic debrief from submitted scorecards. Every point names the rounds it came from.
 * It is advisory: a person records the hire decision. An AI-written summary replaces it in Phase 6.
 */
public final class DebriefBuilder {
    public record Point(String text, List<String> fromRounds) {}

    public record Debrief(
            String applicationId,
            String candidateName,
            int roundsCompleted,
            int roundsTotal,
            List<Point> consistentStrengths,
            List<Point> concerns,
            List<Point> disagreements,
            String generatedBy) {}

    private record Observation(String roundName, String interviewer, Rating rating) {}

    private DebriefBuilder() {}

    public static Debrief build(InterviewLoop loop) {
        var byCompetency = new LinkedHashMap<String, List<Observation>>();
        int total = 0;
        int completed = 0;
        for (var round : loop.rounds()) {
            if (round.status() == RoundStatus.CANCELLED) continue;
            total++;
            if (!round.scorecards().isEmpty()) completed++;
            for (var card : round.scorecards()) {
                for (var entry : card.ratings()) {
                    var label = round.competencies().stream()
                            .filter(value -> value.id().equals(entry.competencyId()))
                            .findFirst()
                            .map(InterviewTypes.Competency::label)
                            .orElse(entry.competencyId());
                    byCompetency
                            .computeIfAbsent(label, key -> new ArrayList<>())
                            .add(new Observation(round.name(), card.interviewerName(), entry.rating()));
                }
            }
        }
        var strengths = new ArrayList<Point>();
        var concerns = new ArrayList<Point>();
        var disagreements = new ArrayList<Point>();
        byCompetency.forEach((label, observations) -> {
            int min = observations.stream()
                    .mapToInt(value -> value.rating().score())
                    .min()
                    .orElse(0);
            int max = observations.stream()
                    .mapToInt(value -> value.rating().score())
                    .max()
                    .orElse(0);
            var rounds = rounds(observations, Set.of(Rating.values()));
            if (max - min >= 2) {
                disagreements.add(new Point(
                        "Interviewers disagree on " + label + ": ratings range from " + name(min) + " to " + name(max)
                                + ".",
                        rounds));
            } else if (min >= Rating.GOOD.score()) {
                strengths.add(new Point(
                        label + " was rated " + name(min) + " or better by " + observations.size()
                                + (observations.size() == 1 ? " interviewer." : " interviewers."),
                        rounds));
            }
            if (min <= Rating.MIXED.score()) {
                var low = rounds(observations, Set.of(Rating.POOR, Rating.MIXED));
                concerns.add(new Point(label + " was rated " + name(min) + " in " + String.join(", ", low) + ".", low));
            }
        });
        return new Debrief(
                loop.applicationId(),
                loop.candidateName(),
                completed,
                total,
                strengths,
                concerns,
                disagreements,
                "RULES");
    }

    private static List<String> rounds(List<Observation> observations, Set<Rating> ratings) {
        var result = new LinkedHashSet<String>();
        observations.stream()
                .filter(value -> ratings.contains(value.rating()))
                .forEach(value -> result.add(value.roundName()));
        return List.copyOf(result);
    }

    private static String name(int score) {
        for (var value : Rating.values())
            if (value.score() == score) return value.name().toLowerCase(java.util.Locale.ROOT);
        return String.valueOf(score);
    }
}
