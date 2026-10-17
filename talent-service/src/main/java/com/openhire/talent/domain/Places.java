package com.openhire.talent.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** City names found in resume text; deliberately small, the structured resume has no location field. */
public final class Places {
    private static final Map<String, List<String>> NAMES = new LinkedHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        add("Bengaluru", "bangalore");
        add("Mumbai", "bombay");
        add("Delhi", "new delhi");
        add("Gurugram", "gurgaon");
        add("Noida");
        add("Pune");
        add("Hyderabad");
        add("Chennai");
        add("Kolkata", "calcutta");
        add("Ahmedabad");
        add("Jaipur");
        add("Kochi", "cochin");
        add("Remote");
        add("London");
        add("New York");
        add("San Francisco");
        add("Singapore");
        add("Dubai");
        add("Berlin");
        add("Toronto");
    }

    private Places() {}

    private static void add(String name, String... aliases) {
        var all = new ArrayList<String>();
        all.add(name.toLowerCase(Locale.ROOT));
        for (var alias : aliases) all.add(alias);
        NAMES.put(name, all);
        PATTERNS.put(
                name,
                Pattern.compile(
                        "(?<![a-z])(?:"
                                + String.join(
                                        "|", all.stream().map(Pattern::quote).toList()) + ")(?![a-z])",
                        Pattern.CASE_INSENSITIVE));
    }

    public static String canonical(String place) {
        if (place == null) return "";
        var key = place.trim().toLowerCase(Locale.ROOT);
        for (var entry : NAMES.entrySet()) if (entry.getValue().contains(key)) return entry.getKey();
        return place.trim();
    }

    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        var found = new ArrayList<String>();
        PATTERNS.forEach((name, pattern) -> {
            if (pattern.matcher(text).find()) found.add(name);
        });
        return found;
    }
}
