package com.openhire.offer.domain;

import com.openhire.offer.domain.OfferTypes.Terms;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the offer letter: loads a template, merges approved terms and clause blocks into plain text, and
 * checksums the result. Plain text keeps the candidate-facing letter free of markup.
 */
public final class OfferRenderer {
    public static final Set<String> VARIABLES = Set.of(
            "candidate.name",
            "job.title",
            "company.name",
            "offer.date",
            "terms.designation",
            "terms.currency",
            "terms.base",
            "terms.variable",
            "terms.bonus",
            "terms.joiningDate",
            "terms.location",
            "terms.reportsTo",
            "terms.expiryDate",
            "coverNote",
            "clauses");

    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z.]+)\\s*}}");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private OfferRenderer() {}

    /** Rejects text that uses merge fields the renderer does not know. */
    public static String checked(String body) {
        if (body == null || body.isBlank()) throw new IllegalArgumentException("The text cannot be empty");
        if (body.length() > 20_000) throw new IllegalArgumentException("The text is too long");
        var matcher = FIELD.matcher(body);
        while (matcher.find())
            if (!VARIABLES.contains(matcher.group(1)))
                throw new IllegalArgumentException("Unknown merge field {{" + matcher.group(1) + "}}");
        return body;
    }

    public record Rendered(String text, String sha256) {}

    public static Rendered render(
            OfferTemplate template,
            List<OfferClause> clauses,
            String candidateName,
            String jobTitle,
            String companyName,
            Terms terms,
            String coverNote,
            LocalDate today) {
        var clauseText = new StringBuilder();
        for (var clause : clauses)
            clauseText
                    .append(clause.title())
                    .append('\n')
                    .append(clause.body().strip())
                    .append("\n\n");
        var values = new LinkedHashMap<String, String>();
        values.put("candidate.name", candidateName);
        values.put("job.title", jobTitle);
        values.put("company.name", companyName);
        values.put("offer.date", LONG_DATE.format(today));
        values.put("terms.designation", terms.designation());
        values.put("terms.currency", terms.currency());
        values.put("terms.base", money(terms.base()));
        values.put("terms.variable", money(terms.variable()));
        values.put("terms.bonus", money(terms.bonus()));
        values.put("terms.joiningDate", LONG_DATE.format(LocalDate.parse(terms.joiningDate())));
        values.put("terms.location", terms.location() == null ? "" : terms.location());
        values.put("terms.reportsTo", terms.reportsTo() == null ? "" : terms.reportsTo());
        values.put("terms.expiryDate", LONG_DATE.format(LocalDate.parse(terms.expiryDate())));
        values.put("coverNote", coverNote == null ? "" : coverNote.strip());
        values.put("clauses", clauseText.toString().strip());
        var out = new StringBuilder();
        var matcher = FIELD.matcher(template.body());
        while (matcher.find()) {
            var value = values.getOrDefault(matcher.group(1), "");
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        var text = out.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
        return new Rendered(text, sha256(text));
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String money(BigDecimal value) {
        var symbols = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
        return new DecimalFormat("#,##0.##", symbols).format(value);
    }
}
