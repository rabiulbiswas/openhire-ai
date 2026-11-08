package com.openhire.candidateservice.service;

import static com.openhire.support.Records.*;

import java.util.*;
import java.util.regex.Pattern;
import org.bson.Document;

/** Conservative, deterministic extraction of stated profile evidence. Never infer skills or employers. */
final class ResumeProfileEvidence {
    private static final Pattern PERIOD =
            Pattern.compile("(?i)\\b(?:19|20)\\d{2}\\b.*(?:\\b(?:19|20)\\d{2}\\b|present|current|now)");

    private ResumeProfileEvidence() {}

    static String textOf(Document resume) {
        String content = text(resume, "text");
        if (!content.isBlank()) return content;
        return String.join(
                "\n",
                doc(resume.get("sections")).entrySet().stream()
                        .map(entry -> entry.getKey() + "\n" + entry.getValue())
                        .toList());
    }

    static Document extract(Document resume) {
        String content = textOf(resume);
        var sections = sections(content);
        var skills = new LinkedHashMap<String, Document>();
        StringBuilder group = new StringBuilder();
        for (String line : sections.getOrDefault("skills", "").split("\\R")) {
            line = line.replaceFirst("^[\\s•·*\\-]+", "").trim();
            if (line.isEmpty()) continue;
            int label = line.indexOf(':');
            if (label >= 0 && label < 65) {
                addSkills(skills, group.toString());
                group = new StringBuilder(line.substring(label + 1));
            } else {
                if (!group.isEmpty())
                    group.append(group.toString().stripTrailing().endsWith(",") ? " " : "; ");
                group.append(line);
            }
        }
        addSkills(skills, group.toString());
        String history = sections.getOrDefault("experience", "").trim();
        var experience = new ArrayList<Document>();
        var lines = history.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        for (int i = 0; i < lines.size() && experience.size() < 50; i++) {
            String line = lines.get(i);
            var oneLine = Pattern.compile("^([^,]{1,200}),\\s*([^,]{1,200}),\\s*(.+)$")
                    .matcher(line);
            if (oneLine.matches() && PERIOD.matcher(oneLine.group(3)).find()) {
                experience.add(new Document("company", oneLine.group(1).trim())
                        .append("title", oneLine.group(2).trim())
                        .append("period", oneLine.group(3).trim()));
            } else if (i > 0 && line.length() <= 200 && PERIOD.matcher(line).find()) {
                String heading = lines.get(i - 1);
                if (heading.length() > 250 || heading.matches("^[•¢*].*")) continue;
                var role = Pattern.compile("^(.+)\\s+(?:[—–|]|at|@)\\s+(.+)$").matcher(heading);
                String period = line.split("\\|", 2)[0].trim();
                if (role.matches()) {
                    experience.add(new Document("company", role.group(2).trim())
                            .append("title", role.group(1).trim())
                            .append("period", period));
                } else {
                    experience.add(new Document("company", "")
                            .append("title", "")
                            .append("period", "")
                            .append("summary", heading + "\n" + line));
                }
            }
        }
        // Unstructured experience is still evidence: show its wording, not invented job history.
        if (experience.isEmpty() && !history.isBlank())
            experience.add(new Document("company", "")
                    .append("title", "")
                    .append("period", "")
                    .append("summary", history.substring(0, Math.min(4000, history.length()))));
        return new Document("skills", new ArrayList<>(skills.values())).append("experience", experience);
    }

    private static void addSkills(Map<String, Document> skills, String group) {
        for (String value : group.split("[,;|•]")) {
            String name = value.trim();
            if (!name.isEmpty() && name.length() <= 120 && skills.size() < 100)
                skills.putIfAbsent(
                        name.toLowerCase(Locale.ROOT), new Document("name", name).append("source", "RESUME"));
        }
    }

    private static Map<String, String> sections(String content) {
        var result = new LinkedHashMap<String, String>();
        String section = "summary";
        for (String line : content.split("\\R")) {
            String heading = line.trim().toLowerCase(Locale.ROOT).replaceAll("[:\\s]+$", "");
            if (Set.of(
                            "skills",
                            "technical skills",
                            "core technical skills",
                            "key skills",
                            "professional skills",
                            "skill set")
                    .contains(heading)) section = "skills";
            else if (Set.of(
                            "experience",
                            "professional experience",
                            "work experience",
                            "employment history",
                            "work history",
                            "career history")
                    .contains(heading)) section = "experience";
            else if (Set.of(
                            "education",
                            "educational qualification",
                            "qualifications",
                            "projects",
                            "certifications",
                            "references",
                            "professional summary",
                            "summary")
                    .contains(heading)) section = heading;
            else result.merge(section, line + "\n", String::concat);
        }
        return result;
    }
}
