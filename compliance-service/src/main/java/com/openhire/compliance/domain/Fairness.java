package com.openhire.compliance.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adverse-impact analysis. For a transition, the selection rate of a group is selected / n among applicants of that
 * group who reached the earlier stage. The adverse impact ratio is the group's rate over the highest rate among groups
 * large enough to judge; below the threshold (the four-fifths rule at 0.8) the group is flagged. Groups smaller than
 * the minimum size are reported but never flagged and never set the benchmark rate.
 */
public final class Fairness {
    public static final List<String> DIMENSIONS = List.of("gender", "ethnicity", "ageBand", "disability");
    public static final List<String[]> TRANSITIONS = List.of(
            new String[] {"APPLIED", "SHORTLISTED"},
            new String[] {"SHORTLISTED", "INTERVIEWING"},
            new String[] {"INTERVIEWING", "OFFER"},
            new String[] {"OFFER", "HIRED"});

    private Fairness() {}

    /** An applicant with a declared group on the dimension and the furthest pipeline rank they reached. */
    public record Applicant(String group, int furthestRank) {}

    public record GroupResult(
            String group,
            int n,
            int selected,
            double selectionRate,
            Double adverseImpactRatio,
            boolean flag,
            boolean insufficientData) {
        public Map<String, Object> view() {
            var map = new LinkedHashMap<String, Object>();
            map.put("group", group);
            map.put("n", n);
            map.put("selected", selected);
            map.put("selectionRate", round(selectionRate));
            map.put("adverseImpactRatio", adverseImpactRatio == null ? null : round(adverseImpactRatio));
            map.put("flag", flag);
            map.put("insufficientData", insufficientData);
            return map;
        }
    }

    public record TransitionResult(String from, String to, Double highestRate, List<GroupResult> groups) {
        public Map<String, Object> view() {
            var map = new LinkedHashMap<String, Object>();
            map.put("from", from);
            map.put("to", to);
            map.put("highestRate", highestRate == null ? null : round(highestRate));
            map.put("groups", groups.stream().map(GroupResult::view).toList());
            return map;
        }
    }

    public static List<TransitionResult> analyse(List<Applicant> applicants, int minGroupSize, double threshold) {
        var results = new ArrayList<TransitionResult>();
        for (var transition : TRANSITIONS) {
            int fromRank = Stages.rank(transition[0]), toRank = Stages.rank(transition[1]);
            var counts = new TreeMap<String, int[]>();
            for (var applicant : applicants) {
                if (applicant.furthestRank() < fromRank) continue;
                var tally = counts.computeIfAbsent(applicant.group(), key -> new int[2]);
                tally[0]++;
                if (applicant.furthestRank() >= toRank) tally[1]++;
            }
            double highest = -1;
            for (var tally : counts.values())
                if (tally[0] >= minGroupSize) highest = Math.max(highest, (double) tally[1] / tally[0]);
            var groups = new ArrayList<GroupResult>();
            for (var entry : counts.entrySet()) {
                int n = entry.getValue()[0], selected = entry.getValue()[1];
                double rate = n == 0 ? 0 : (double) selected / n;
                boolean insufficient = n < minGroupSize;
                Double ratio = insufficient || highest <= 0 ? null : rate / highest;
                groups.add(new GroupResult(
                        entry.getKey(), n, selected, rate, ratio, ratio != null && ratio < threshold, insufficient));
            }
            results.add(new TransitionResult(transition[0], transition[1], highest < 0 ? null : highest, groups));
        }
        return results;
    }

    static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
