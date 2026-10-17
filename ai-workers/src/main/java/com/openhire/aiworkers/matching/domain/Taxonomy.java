package com.openhire.aiworkers.matching.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Skill synonym groups ("spring boot" ~ "spring", "kafka" ~ "event streaming") for the lexical stage. */
public final class Taxonomy {
    private static final String RESOURCE = "/taxonomy/skills.txt";

    private final Map<String, Set<String>> groups = new HashMap<>();

    public Taxonomy(List<String> lines) {
        for (String line : lines) {
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            var group = new LinkedHashSet<String>();
            for (String term : trimmed.split(",")) group.add(normalize(term));
            for (String term : group)
                groups.merge(term, group, (a, b) -> {
                    var merged = new LinkedHashSet<>(a);
                    merged.addAll(b);
                    return merged;
                });
        }
    }

    public static Taxonomy builtIn() {
        try (InputStream in = Taxonomy.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is missing");
            var lines = new ArrayList<String>();
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) lines.add(line);
            }
            return new Taxonomy(lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The term itself plus every synonym, normalised. */
    public Set<String> variants(String term) {
        var key = normalize(term);
        return groups.getOrDefault(key, Set.of(key));
    }

    static String normalize(String term) {
        return term.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
