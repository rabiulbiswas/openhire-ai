package com.openhire.talent.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A small built-in taxonomy: the fallback when the model is down and the basis of rule coverage. */
public final class SkillTaxonomy {
    private static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();
    private static final Map<String, String> CANONICAL = new LinkedHashMap<>();

    static {
        add("java", "jdk", "j2ee");
        add("javascript", "js", "ecmascript");
        add("typescript", "ts");
        add("python");
        add("go", "golang");
        add("rust");
        add("c++", "cpp");
        add("c#", "csharp", ".net", "dotnet");
        add("kotlin");
        add("swift");
        add("ruby", "ruby on rails", "rails");
        add("php", "laravel");
        add("scala");
        add("sql", "t-sql", "pl/sql");
        add("postgresql", "postgres");
        add("mysql");
        add("mongodb", "mongo");
        add("redis");
        add("kafka", "apache kafka");
        add("elasticsearch", "opensearch");
        add("aws", "amazon web services");
        add("azure");
        add("gcp", "google cloud");
        add("docker", "containers");
        add("kubernetes", "k8s");
        add("terraform", "infrastructure as code");
        add("ci/cd", "jenkins", "github actions", "gitlab ci");
        add("linux");
        add("git");
        add("react", "react.js", "reactjs");
        add("angular");
        add("vue", "vue.js", "vuejs");
        add("node.js", "nodejs", "express.js");
        add("spring boot", "spring");
        add("django");
        add("flask");
        add("fastapi");
        add("graphql");
        add("rest apis", "rest", "restful");
        add("microservices", "micro-services");
        add("system design", "distributed systems");
        add("machine learning", "ml");
        add("deep learning", "neural networks");
        add("nlp", "natural language processing");
        add("pytorch");
        add("tensorflow");
        add("data engineering", "etl", "data pipelines");
        add("spark", "apache spark", "pyspark");
        add("airflow");
        add("data analysis", "data analytics");
        add("tableau");
        add("power bi", "powerbi");
        add("excel", "spreadsheets");
        add("product management", "product roadmap");
        add("agile", "scrum", "kanban");
        add("project management", "pmp");
        add("figma");
        add("ux design", "ui/ux", "user experience");
        add("sales", "business development");
        add("recruiting", "talent acquisition");
        add("accounting", "bookkeeping");
        add("customer support", "customer success");
        add("security", "appsec", "infosec");
        add("testing", "qa", "test automation", "selenium");
    }

    private SkillTaxonomy() {}

    private static void add(String name, String... synonyms) {
        var all = new ArrayList<String>();
        all.add(name);
        all.addAll(List.of(synonyms));
        SYNONYMS.put(name, all);
        var alternatives = all.stream().map(Pattern::quote).toList();
        PATTERNS.put(
                name,
                Pattern.compile(
                        "(?<![a-z0-9+#])(?:" + String.join("|", alternatives) + ")(?![a-z0-9+#])",
                        Pattern.CASE_INSENSITIVE));
        for (var value : all) CANONICAL.putIfAbsent(value, name);
    }

    /** The canonical name for a skill phrase; unknown phrases are only trimmed and lower-cased. */
    public static String canonical(String skill) {
        if (skill == null) return "";
        var key = skill.trim().toLowerCase(Locale.ROOT);
        return CANONICAL.getOrDefault(key, key);
    }

    public static List<String> canonical(List<String> skills) {
        return skills == null
                ? List.of()
                : skills.stream()
                        .map(SkillTaxonomy::canonical)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
    }

    /** Taxonomy skills mentioned in the text, in taxonomy order. */
    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        var found = new ArrayList<String>();
        PATTERNS.forEach((name, pattern) -> {
            if (pattern.matcher(text).find()) found.add(name);
        });
        return found;
    }
}
