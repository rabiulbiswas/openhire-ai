package com.openhire.aiworkers.matching.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits resume text into overlapping windows of about 400 words (a token approximation), never crossing a section
 * heading. Chunk text is a slice of the original, so quotes the judge returns still match the resume text.
 */
public final class Chunker {
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Map<String, String> HEADINGS = Map.ofEntries(
            Map.entry("summary", "SUMMARY"),
            Map.entry("profile", "SUMMARY"),
            Map.entry("objective", "SUMMARY"),
            Map.entry("experience", "EXPERIENCE"),
            Map.entry("work experience", "EXPERIENCE"),
            Map.entry("professional experience", "EXPERIENCE"),
            Map.entry("employment", "EXPERIENCE"),
            Map.entry("employment history", "EXPERIENCE"),
            Map.entry("skills", "SKILLS"),
            Map.entry("technical skills", "SKILLS"),
            Map.entry("key skills", "SKILLS"),
            Map.entry("education", "EDUCATION"),
            Map.entry("projects", "PROJECTS"),
            Map.entry("certifications", "CERTIFICATIONS"),
            Map.entry("achievements", "ACHIEVEMENTS"),
            Map.entry("publications", "PUBLICATIONS"));

    private final int size;
    private final int overlap;
    private final int max;

    public Chunker() {
        this(400, 60, 500);
    }

    public Chunker(int size, int overlap, int max) {
        this.size = size;
        this.overlap = overlap;
        this.max = max;
    }

    public List<Chunk> chunk(String resumeId, String text) {
        var chunks = new ArrayList<Chunk>();
        for (var section : sections(text)) {
            var words = new ArrayList<int[]>();
            var matcher = WORD.matcher(section.body());
            while (matcher.find()) words.add(new int[] {matcher.start(), matcher.end()});
            int n = words.size();
            // Evenly sized overlapping windows: no tiny trailing chunk, every window at most `size` words.
            int count = n <= max ? 1 : (int) Math.ceil((n - overlap) / (double) (size - overlap));
            int length = count == 1 ? n : (int) Math.ceil((n + (count - 1) * overlap) / (double) count);
            for (int i = 0; i < count; i++) {
                int start = i * (length - overlap);
                int end = i == count - 1 ? n : Math.min(n, start + length);
                var slice = section.body()
                        .substring(words.get(start)[0], words.get(end - 1)[1])
                        .strip();
                int index = chunks.size();
                chunks.add(new Chunk(
                        "chk_"
                                + Hashes.sha256(resumeId, String.valueOf(index), slice)
                                        .substring(0, 24),
                        index,
                        section.label(),
                        slice));
            }
        }
        return chunks;
    }

    private record Section(String label, String body) {}

    private static List<Section> sections(String text) {
        var result = new ArrayList<Section>();
        String label = "SUMMARY";
        var body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            var heading = heading(line);
            if (heading != null) {
                if (!body.toString().isBlank()) result.add(new Section(label, body.toString()));
                label = heading;
                body = new StringBuilder();
            } else body.append(line).append('\n');
        }
        if (!body.toString().isBlank()) result.add(new Section(label, body.toString()));
        return result;
    }

    private static String heading(String line) {
        var trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.length() > 40) return null;
        if (trimmed.endsWith(":"))
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        return HEADINGS.get(trimmed.toLowerCase(Locale.ROOT));
    }
}
