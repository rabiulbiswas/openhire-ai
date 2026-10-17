package com.openhire.talent.domain;

import com.openhire.talent.domain.TalentTypes.Filters;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Structured filter evaluated in memory over the consented pool. Unknown values never satisfy a filter. */
public final class ProfileFilter {
    private ProfileFilter() {}

    public static boolean matches(TalentProfile profile, Filters filters, Instant now) {
        if (!filters.locations().isEmpty()) {
            var have = profile.locations().stream().map(Places::canonical).toList();
            if (filters.locations().stream().map(Places::canonical).noneMatch(have::contains)) return false;
        }
        if (!filters.skills().isEmpty()) {
            var have = SkillTaxonomy.canonical(profile.skills());
            if (!have.containsAll(SkillTaxonomy.canonical(filters.skills()))) return false;
        }
        if (filters.minYears() != null && (profile.totalYears() == null || profile.totalYears() < filters.minYears()))
            return false;
        if (filters.lastInterviewedWithinDays() != null
                && !profile.interviewedWithin(now.minus(Duration.ofDays(filters.lastInterviewedWithinDays()))))
            return false;
        if (filters.openToRelocate() != null && !filters.openToRelocate().equals(profile.openToRelocate()))
            return false;
        return filters.stage() == null
                || filters.stage().isBlank()
                || profile.reachedStage(normalizeStage(filters.stage()));
    }

    /** Accepts the model's loose wording ("interview", "shortlisted") for a pipeline stage. */
    public static String normalizeStage(String stage) {
        var value = stage.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case "INTERVIEW", "INTERVIEWED" -> "INTERVIEWING";
            case "SHORTLIST" -> "SHORTLISTED";
            case "REJECT" -> "REJECTED";
            default -> value;
        };
    }

    /** Human-readable chips describing a filter, used when the model did not supply any. */
    public static List<String> chips(Filters filters) {
        var chips = new java.util.ArrayList<String>();
        filters.skills().forEach(skill -> chips.add("Skill: " + skill));
        filters.locations().forEach(place -> chips.add("Location: " + place));
        if (filters.minYears() != null) chips.add(filters.minYears() + "+ years of experience");
        if (filters.lastInterviewedWithinDays() != null)
            chips.add("Interviewed in the last " + filters.lastInterviewedWithinDays() + " days");
        if (Boolean.TRUE.equals(filters.openToRelocate())) chips.add("Open to relocate");
        if (filters.stage() != null && !filters.stage().isBlank()) chips.add("Stage: " + filters.stage());
        return chips;
    }
}
