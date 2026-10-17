package com.openhire.talent.domain;

import com.openhire.talent.domain.TalentTypes.Evidence;
import com.openhire.talent.domain.TalentTypes.StageStep;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Deterministic, source-labelled statements: the explanation when the model is unavailable, and model input. */
public final class Narratives {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private Narratives() {}

    /** CURRENT evidence only: what the present profile says against the job's requirements. */
    public static String whyFitNow(TalentProfile profile, List<String> required) {
        var wanted = SkillTaxonomy.canonical(required);
        var have = SkillTaxonomy.canonical(profile.skills());
        var matched = wanted.stream().filter(have::contains).toList();
        var parts = new ArrayList<String>();
        if (!wanted.isEmpty())
            parts.add("Current profile covers " + matched.size() + " of " + wanted.size() + " required skills"
                    + (matched.isEmpty() ? "" : " (" + String.join(", ", matched) + ")"));
        else if (!have.isEmpty())
            parts.add("Current profile lists " + have.stream().limit(5).collect(Collectors.joining(", ")));
        if (profile.totalYears() != null)
            parts.add(String.format(Locale.ROOT, "%.0f years of experience", profile.totalYears()));
        var text = parts.isEmpty() ? "No current skills could be read from the resume" : String.join("; ", parts);
        return "CURRENT: " + text + ". Rule-based summary, not an AI explanation.";
    }

    /** HISTORICAL evidence only: what happened in past applications. Never mixed into the current fit. */
    public static String historicalEvidence(TalentProfile profile) {
        var lines = historyLines(profile);
        if (lines.isEmpty()) return "HISTORICAL: No earlier application is recorded.";
        return "HISTORICAL: " + String.join(" ", lines);
    }

    /** One sentence per past application, built from stage movement. */
    public static List<String> historyLines(TalentProfile profile) {
        var lines = new ArrayList<String>();
        for (var ref : profile.applications()) {
            var path = new ArrayList<String>();
            for (StageStep step : profile.stageHistory())
                if (ref.applicationId().equals(step.applicationId())) path.add(step.to());
            var line = new StringBuilder("Applied for ")
                    .append(ref.jobTitle() == null || ref.jobTitle().isBlank() ? "a role" : ref.jobTitle());
            if (ref.appliedAt() != null)
                line.append(" in ").append(MONTH.format(ref.appliedAt().atZone(ZoneOffset.UTC)));
            if (!path.isEmpty()) line.append("; moved through ").append(String.join(", ", path));
            line.append("; now ")
                    .append(ref.stage() == null ? "unknown" : ref.stage())
                    .append('.');
            lines.add(line.toString());
        }
        if (profile.silverMedalist()) lines.add("Reached interviews and was not hired.");
        return lines;
    }

    /** Past assessment notes as statements for the explanation model, always labelled historical. */
    public static List<String> assessmentNotes(TalentProfile profile) {
        return profile.historical().stream().map(Evidence::text).toList();
    }
}
