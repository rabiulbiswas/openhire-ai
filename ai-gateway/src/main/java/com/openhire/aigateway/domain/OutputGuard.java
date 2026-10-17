package com.openhire.aigateway.domain;

import com.openhire.aigateway.domain.AiTypes.GuardRejection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Task-specific checks on a model reply that has already passed its JSON schema: grounding of quotes, clamping,
 * protected-attribute scan and deterministic clean-up. A {@link GuardRejection} asks the gateway for one repair retry.
 */
public final class OutputGuard {
    private static final int MAX_QUOTE = 400;
    private static final Map<String, String> BIAS_TERMS = biasTerms();

    private OutputGuard() {}

    public record Chunk(String chunkId, String text) {}

    public record Evidence(String chunkId, String quote) {}

    public record JudgeOutput(
            int score,
            int rawScore,
            List<Evidence> evidence,
            List<String> strengths,
            List<String> gaps,
            String rationale,
            boolean insufficientEvidence,
            boolean grounded,
            int quotesTotal,
            int quotesGrounded) {}

    /** Validates a judge reply against the chunks the model was shown. Ungrounded quotes never fail the call. */
    public static JudgeOutput judge(Map<String, Object> json, List<Chunk> chunks) {
        int raw = (int) Math.round(number(json.get("score"), 0));
        int score = Math.max(0, Math.min(5, raw));
        var evidence = new ArrayList<Evidence>();
        int total = 0;
        int grounded = 0;
        for (var item : Json.list(json.get("evidence"))) {
            var row = Json.map(item);
            var quote = Json.string(row.get("quote"));
            if (quote.isBlank()) continue;
            total++;
            var declared = Json.string(row.get("chunkId"));
            var home = chunks.stream()
                    .filter(c -> c.chunkId().equals(declared) && Text.contains(c.text(), quote))
                    .findFirst()
                    .or(() -> chunks.stream()
                            .filter(c -> Text.contains(c.text(), quote))
                            .findFirst());
            if (home.isPresent() && quote.length() <= MAX_QUOTE && !InputGuard.instructionLike(quote)) {
                grounded++;
                evidence.add(
                        new Evidence(home.get().chunkId(), exactQuote(home.get().text(), quote)));
            }
        }
        var strengths = sentences(json.get("strengths"), 3);
        var gaps = sentences(json.get("gaps"), 3);
        var rationale = Json.string(json.get("rationale"));
        var protectedTerms = ProtectedAttributes.scan(String.join(". ", strengths), String.join(". ", gaps), rationale);
        if (!protectedTerms.isEmpty())
            throw new GuardRejection(
                    "The answer mentions protected characteristics (" + String.join(", ", protectedTerms)
                            + "). Judge only skills and experience and do not mention personal attributes.");
        boolean insufficient = evidence.isEmpty() || grounded < total;
        return new JudgeOutput(
                insufficient ? 0 : score,
                raw,
                List.copyOf(evidence),
                strengths,
                gaps,
                rationale,
                insufficient,
                !insufficient,
                total,
                grounded);
    }

    /** The substring of the chunk that matches the quote, so callers can highlight it verbatim. */
    static String exactQuote(String chunk, String quote) {
        var wanted = Text.normalizeQuote(quote);
        if (chunk.contains(quote.strip())) return quote.strip();
        // Same text modulo case, spacing and typographic quotes: locate the original span word by word.
        var words = wanted.split(" ");
        var pattern = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) pattern.append("\\s+");
            pattern.append(Pattern.quote(words[i]));
        }
        var matcher = Pattern.compile(pattern.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(chunk);
        return matcher.find() ? matcher.group() : quote.strip();
    }

    // ---- resume structure ----

    private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})");
    private static final Pattern MONTH_YEAR = Pattern.compile("^(\\d{1,2})[-/.](\\d{4})$");
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");
    private static final List<String> MONTHS =
            List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Pattern OPEN_END = Pattern.compile("(?i)present|current|now|ongoing|till date|to date");

    public static Map<String, Object> structure(Map<String, Object> json, String resumeText, LocalDate today) {
        var out = new LinkedHashMap<String, Object>();
        out.put("headline", clip(Json.string(json.get("headline")), 140));
        var haystack = Text.normalize(resumeText);
        var skills = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        for (var item : Json.list(json.get("skills"))) {
            var skill = clip(Json.string(item), 60);
            var key = Text.normalize(skill);
            if (key.length() >= 2 && haystack.contains(key) && seen.add(key)) skills.add(skill);
            if (skills.size() >= 40) break;
        }
        out.put("skills", List.copyOf(skills));
        var roles = new ArrayList<Map<String, Object>>();
        var spans = new ArrayList<YearMonth[]>();
        for (var item : Json.list(json.get("roles"))) {
            var row = Json.map(item);
            var title = clip(Json.string(row.get("title")), 120);
            var company = clip(Json.string(row.get("company")), 120);
            if (title.isBlank() && company.isBlank()) continue;
            var start = yearMonth(Json.string(row.get("start")), true, today);
            var openEnded = Json.string(row.get("end")).isBlank()
                    || OPEN_END.matcher(Json.string(row.get("end"))).find();
            var end = openEnded ? null : yearMonth(Json.string(row.get("end")), false, today);
            var role = new LinkedHashMap<String, Object>();
            role.put("title", title);
            role.put("company", company);
            role.put("start", start == null ? null : start.toString());
            role.put("end", end == null ? null : end.toString());
            role.put("fullTime", !(row.get("fullTime") instanceof Boolean b) || b);
            roles.add(role);
            if (start != null) {
                var last = end == null ? YearMonth.from(today) : end;
                if (!last.isBefore(start)) spans.add(new YearMonth[] {start, last});
            }
        }
        out.put("roles", roles);
        var education = new ArrayList<Map<String, Object>>();
        for (var item : Json.list(json.get("education"))) {
            var row = Json.map(item);
            var institution = clip(Json.string(row.get("institution")), 160);
            var degree = clip(Json.string(row.get("degree")), 160);
            if (institution.isBlank() && degree.isBlank()) continue;
            var edu = new LinkedHashMap<String, Object>();
            edu.put("institution", institution);
            edu.put("degree", degree);
            var year = YEAR.matcher(Json.string(row.get("year")));
            edu.put("year", year.find() ? Integer.valueOf(year.group(1)) : null);
            education.add(edu);
        }
        out.put("education", education);
        out.put(
                "totalYearsExperience",
                spans.isEmpty() ? clampYears(number(json.get("totalYearsExperience"), 0)) : years(spans));
        return out;
    }

    private static double clampYears(double years) {
        return Math.round(Math.max(0, Math.min(60, years)) * 10) / 10.0;
    }

    /** Months covered by the union of the roles, so overlapping jobs are not counted twice. */
    static double years(List<YearMonth[]> spans) {
        var sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> a[0].compareTo(b[0]));
        long months = 0;
        YearMonth coveredTo = null;
        for (var span : sorted) {
            var from = coveredTo != null && !span[0].isAfter(coveredTo) ? coveredTo.plusMonths(1) : span[0];
            if (!span[1].isBefore(from)) {
                months += java.time.temporal.ChronoUnit.MONTHS.between(from, span[1]) + 1;
                coveredTo = coveredTo == null || span[1].isAfter(coveredTo) ? span[1] : coveredTo;
            }
        }
        return Math.round(months / 12.0 * 10) / 10.0;
    }

    static YearMonth yearMonth(String raw, boolean start, LocalDate today) {
        if (raw == null || raw.isBlank()) return null;
        var text = raw.strip().toLowerCase(Locale.ROOT);
        YearMonth value = null;
        var ym = YEAR_MONTH.matcher(text);
        var my = MONTH_YEAR.matcher(text);
        if (ym.find() && validMonth(ym.group(2)))
            value = YearMonth.of(Integer.parseInt(ym.group(1)), Integer.parseInt(ym.group(2)));
        else if (my.find() && validMonth(my.group(1)))
            value = YearMonth.of(Integer.parseInt(my.group(2)), Integer.parseInt(my.group(1)));
        else {
            var year = YEAR.matcher(text);
            if (year.find()) {
                int month = 0;
                for (int i = 0; i < MONTHS.size(); i++) if (text.contains(MONTHS.get(i))) month = i + 1;
                value = YearMonth.of(Integer.parseInt(year.group(1)), month > 0 ? month : (start ? 1 : 12));
            }
        }
        if (value == null || value.getYear() < 1950) return null;
        return value.isAfter(YearMonth.from(today)) ? YearMonth.from(today) : value;
    }

    private static boolean validMonth(String month) {
        int m = Integer.parseInt(month);
        return m >= 1 && m <= 12;
    }

    // ---- job description draft ----

    public static Map<String, Object> jobDraft(Map<String, Object> json, String title, List<String> mustHaveSkills) {
        var out = new LinkedHashMap<String, Object>();
        var description = Json.string(json.get("description")).replaceAll("\\r\\n?", "\n");
        if (description.isBlank()) throw new GuardRejection("The description is empty.");
        out.put("description", description);
        var rubric = new ArrayList<Map<String, Object>>();
        var ids = new LinkedHashSet<String>();
        for (var item : Json.list(json.get("rubric"))) {
            var row = Json.map(item);
            var label = clip(Json.string(row.get("label")).replace('_', ' '), 80);
            if (label.isBlank() || rubric.size() >= 8) continue;
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            var requirement = "NICE".equalsIgnoreCase(Json.string(row.get("requirement"))) ? "NICE" : "MUST";
            rubric.add(rubricRow(slug(label, ids), label, number(row.get("weight"), 10), requirement));
        }
        for (var skill : mustHaveSkills) {
            var covered = rubric.stream()
                    .anyMatch(r -> Text.normalize(r.get("label").toString()).contains(Text.normalize(skill)));
            if (!covered && !skill.isBlank()) {
                if (rubric.size() >= 8) rubric.remove(rubric.size() - 1);
                rubric.add(rubricRow(slug(skill, ids), clip(skill, 80), 10, "MUST"));
            }
        }
        if (rubric.isEmpty()) throw new GuardRejection("The rubric is empty.");
        normalizeWeights(rubric);
        out.put("rubric", rubric);
        var knockouts = new ArrayList<Map<String, Object>>();
        for (var item : Json.list(json.get("knockoutQuestions"))) {
            var row = Json.map(item);
            var question = clip(Json.string(row.get("question")), 240);
            if (question.isBlank() || knockouts.size() >= 5) continue;
            var answer = Json.string(row.get("requiredAnswer"));
            var entry = new LinkedHashMap<String, Object>();
            entry.put("question", question);
            entry.put("requiredAnswer", answer.isBlank() ? "Yes" : clip(answer, 80));
            knockouts.add(entry);
        }
        out.put("knockoutQuestions", knockouts);
        var plan = new ArrayList<Map<String, Object>>();
        for (var item : Json.list(json.get("interviewPlan"))) {
            var row = Json.map(item);
            var name = clip(Json.string(row.get("name")), 80);
            if (name.isBlank() || plan.size() >= 6) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", name);
            entry.put("durationMin", (int) Math.max(15, Math.min(120, number(row.get("durationMin"), 45))));
            plan.add(entry);
        }
        if (plan.isEmpty()) {
            plan.add(Map.of("name", "Technical interview", "durationMin", 60));
            plan.add(Map.of("name", "Hiring manager conversation", "durationMin", 45));
        }
        out.put("interviewPlan", plan);
        var flags = new ArrayList<Map<String, Object>>();
        var phrases = new LinkedHashSet<String>();
        for (var item : Json.list(json.get("biasFlags"))) {
            var row = Json.map(item);
            var phrase = clip(Json.string(row.get("phrase")), 80);
            if (!phrase.isBlank()
                    && Text.normalize(description + " " + title).contains(Text.normalize(phrase))
                    && phrases.add(Text.normalize(phrase))) flags.add(flag(phrase, Json.string(row.get("suggestion"))));
        }
        for (var flag : biasFlags(description + "\n" + title))
            if (phrases.add(Text.normalize(flag.get("phrase").toString()))) flags.add(flag);
        out.put("biasFlags", flags);
        return out;
    }

    private static Map<String, Object> rubricRow(String id, String label, double weight, String requirement) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("label", label);
        row.put("weight", Math.max(1, weight));
        row.put("requirement", requirement);
        return row;
    }

    /** Scales weights to whole numbers that add up to exactly 100. */
    static void normalizeWeights(List<Map<String, Object>> rubric) {
        double sum = rubric.stream()
                .mapToDouble(r -> ((Number) r.get("weight")).doubleValue())
                .sum();
        int assigned = 0;
        for (int i = 0; i < rubric.size(); i++) {
            int weight = i == rubric.size() - 1
                    ? 100 - assigned
                    : (int) Math.max(1, Math.round(((Number) rubric.get(i).get("weight")).doubleValue() / sum * 100));
            rubric.get(i).put("weight", weight);
            assigned += weight;
        }
        if (rubric.get(rubric.size() - 1).get("weight") instanceof Integer last && last < 1) {
            rubric.get(rubric.size() - 1).put("weight", 1);
            rubric.get(0).put("weight", (Integer) rubric.get(0).get("weight") - 1);
        }
    }

    private static String slug(String label, java.util.Set<String> used) {
        var base = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isBlank()) base = "criterion";
        if (base.length() > 40) base = base.substring(0, 40);
        var id = base;
        int n = 2;
        while (!used.add(id)) id = base + "-" + n++;
        return id;
    }

    private static Map<String, String> biasTerms() {
        var m = new LinkedHashMap<String, String>();
        m.put("rockstar", "Describe the skills you need instead, for example \"experienced engineer\".");
        m.put("ninja", "Use a plain role title such as \"engineer\".");
        m.put("guru", "Use \"expert\" or describe the depth of experience required.");
        m.put("digital native", "Describe the technical skills required; this phrase signals an age preference.");
        m.put("young", "Remove age references; describe the skills and attitude needed.");
        m.put("energetic", "Describe the pace and scope of the work instead.");
        m.put("recent graduate", "State the experience level (for example 0-2 years) instead.");
        m.put("fresh graduate", "State the experience level (for example 0-2 years) instead.");
        m.put("culture fit", "Use \"values add\" and name the values you look for.");
        m.put("manpower", "Use \"staffing\" or \"workforce\".");
        m.put("salesman", "Use \"salesperson\".");
        m.put("chairman", "Use \"chairperson\".");
        m.put("aggressive", "Use \"proactive\" or \"driven\".");
        m.put("dominant", "Use \"confident\" or describe the leadership expected.");
        m.put("man-hours", "Use \"person-hours\".");
        m.put("he or she", "Use \"they\" or address the reader as \"you\".");
        m.put("native english speaker", "Ask for \"fluent English\" or the communication level needed.");
        return m;
    }

    /** Deterministic scan of a job text for exclusionary wording; complements the model's own flags. */
    public static List<Map<String, Object>> biasFlags(String text) {
        var normalized = Text.normalize(text);
        var out = new ArrayList<Map<String, Object>>();
        BIAS_TERMS.forEach((term, suggestion) -> {
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(term) + "(?![a-z])")
                    .matcher(normalized)
                    .find()) out.add(flag(term, suggestion));
        });
        return out;
    }

    private static Map<String, Object> flag(String phrase, String suggestion) {
        var row = new LinkedHashMap<String, Object>();
        row.put("phrase", phrase);
        row.put("suggestion", suggestion.isBlank() ? "Consider more inclusive wording." : suggestion);
        return row;
    }

    // ---- candidate messages ----

    public static String message(Map<String, Object> json, String purpose, String firstName) {
        var text = Json.string(json.get("text")).replaceAll("\\r\\n?", "\n");
        var lines = new ArrayList<String>();
        for (var line : text.split("\n"))
            if (!line.matches("(?i)\\s*\\[(your|recruiter|company|hiring|sender)[^\\]]*]\\s*,?")) lines.add(line);
        text = String.join("\n", lines).strip();
        if (text.length() < 20) throw new GuardRejection("The message is too short to use.");
        var head = text.substring(0, Math.min(text.length(), 60)).toLowerCase(Locale.ROOT);
        if (firstName != null
                && !firstName.isBlank()
                && !head.contains(firstName.strip().toLowerCase(Locale.ROOT)))
            throw new GuardRejection("Start the message by greeting " + firstName.strip()
                    + " by first name, for example \"Hi " + firstName.strip() + ",\".");
        if (text.length() > 2000) text = text.substring(0, 2000).strip();
        if (text.contains("{{") || text.matches("(?is).*\\[(?:candidate|name|first)[^\\]]*].*"))
            throw new GuardRejection("The message contains unfilled placeholders; write the final text.");
        if ("REJECTION_FEEDBACK".equals(purpose)) {
            var terms = ProtectedAttributes.scan(text);
            if (!terms.isEmpty())
                throw new GuardRejection("The message mentions protected characteristics (" + String.join(", ", terms)
                        + "). Remove them.");
        }
        return text;
    }

    // ---- natural-language filter ----

    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(?:years?|yrs?)");
    private static final Pattern LAST_UNITS = Pattern.compile(
            "(?:last|past|previous|within(?: the)?(?: last)?)\\s+(\\d{1,3}|a|one|two|three|six|twelve)\\s*(day|week|month|year)s?");
    private static final List<String> STAGES = List.of(
            "APPLIED",
            "NEEDS_ATTENTION",
            "AI_ASSESSED",
            "SHORTLISTED",
            "INTERVIEWING",
            "DECISION_PENDING",
            "OFFER",
            "HIRED",
            "REJECTED",
            "WITHDRAWN");

    public static Map<String, Object> nlFilter(Map<String, Object> json, String query) {
        var q = Text.normalize(query);
        var filters = Json.map(json.get("filters"));
        var out = new LinkedHashMap<String, Object>();
        var locations = grounded(filters.get("locations"), q, 5);
        var skills = grounded(filters.get("skills"), q, 10);
        Integer minYears = null;
        var years = YEARS.matcher(q);
        if (years.find()) minYears = Integer.parseInt(years.group(1));
        Integer within = null;
        var last = LAST_UNITS.matcher(q);
        if (last.find()) within = days(last.group(1), last.group(2));
        else if (q.contains("last year") || q.contains("past year")) within = 365;
        else if (q.contains("last quarter")) within = 90;
        Boolean relocate = null;
        if (q.contains("relocat"))
            relocate = !(q.contains("not open to relocat")
                    || q.contains("won't relocate")
                    || q.contains("cannot relocate"));
        String stage = null;
        var proposed =
                Json.string(filters.get("stage")).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (STAGES.contains(proposed)
                && q.contains(proposed.toLowerCase(Locale.ROOT)
                        .replace('_', ' ')
                        .substring(0, Math.min(5, proposed.length())))) stage = proposed;
        var filterOut = new LinkedHashMap<String, Object>();
        filterOut.put("locations", locations);
        filterOut.put("skills", skills);
        filterOut.put("minYears", minYears);
        filterOut.put("lastInterviewedWithinDays", within);
        filterOut.put("openToRelocate", relocate);
        filterOut.put("stage", stage);
        out.put("filters", filterOut);
        var semantic = clip(Json.string(json.get("semanticQuery")), 300);
        out.put("semanticQuery", semantic.isBlank() ? clip(query.strip(), 300) : semantic);
        var chips = new ArrayList<String>();
        locations.forEach(l -> chips.add("Location: " + l));
        skills.forEach(s -> chips.add("Skill: " + s));
        if (minYears != null) chips.add(minYears + "+ years experience");
        if (within != null) chips.add("Interviewed in the last " + within + " days");
        if (relocate != null) chips.add(relocate ? "Open to relocate" : "Not open to relocate");
        if (stage != null) chips.add("Stage: " + stage.toLowerCase(Locale.ROOT).replace('_', ' '));
        out.put("chips", chips);
        return out;
    }

    private static int days(String amount, String unit) {
        int n =
                switch (amount) {
                    case "a", "one" -> 1;
                    case "two" -> 2;
                    case "three" -> 3;
                    case "six" -> 6;
                    case "twelve" -> 12;
                    default -> Integer.parseInt(amount);
                };
        return n
                * switch (unit) {
                    case "week" -> 7;
                    case "month" -> 30;
                    case "year" -> 365;
                    default -> 1;
                };
    }

    /** Keeps only values that really occur in the query, so the model cannot invent filters. */
    private static List<String> grounded(Object values, String query, int max) {
        var out = new LinkedHashSet<String>();
        for (var item : Json.list(values)) {
            var value = clip(Json.string(item), 60);
            if (value.length() >= 2 && query.contains(Text.normalize(value))) out.add(value);
            if (out.size() >= max) break;
        }
        return List.copyOf(out);
    }

    // ---- fit explanation ----

    public record Statement(String statement, String source, String chunkId, String quote) {}

    /** Grounds every statement in the chunks of its own source (CURRENT or HISTORICAL) and drops the rest. */
    public static List<Statement> statements(Object rows, String source, List<Chunk> chunks) {
        var out = new ArrayList<Statement>();
        for (var item : Json.list(rows)) {
            var row = Json.map(item);
            var statement = clip(Json.string(row.get("statement")), 300);
            var quote = Json.string(row.get("quote"));
            if (statement.isBlank() || quote.isBlank() || out.size() >= 4) continue;
            var declared = Json.string(row.get("chunkId"));
            var home = chunks.stream()
                    .filter(c -> c.chunkId().equals(declared) && Text.contains(c.text(), quote))
                    .findFirst()
                    .or(() -> chunks.stream()
                            .filter(c -> Text.contains(c.text(), quote))
                            .findFirst());
            if (home.isPresent() && !InputGuard.instructionLike(quote))
                out.add(new Statement(
                        statement,
                        source,
                        home.get().chunkId(),
                        exactQuote(home.get().text(), quote)));
        }
        var terms =
                ProtectedAttributes.scan(out.stream().map(Statement::statement).toArray(String[]::new));
        if (!terms.isEmpty())
            throw new GuardRejection("The explanation mentions protected characteristics (" + String.join(", ", terms)
                    + "). Describe only skills and experience.");
        return out;
    }

    // ---- helpers ----

    private static List<String> sentences(Object value, int max) {
        var out = new ArrayList<String>();
        for (var item : Json.list(value)) {
            var s = clip(Json.string(item), 240);
            if (!s.isBlank() && out.size() < max) out.add(s);
        }
        return List.copyOf(out);
    }

    static double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) {
            var m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(value.toString());
            if (m.find()) return Double.parseDouble(m.group());
        }
        return fallback;
    }

    static String clip(String value, int max) {
        var v = value == null ? "" : value.strip();
        return v.length() <= max ? v : v.substring(0, max).strip();
    }
}
