package com.openhire.aigateway.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Finds references to protected characteristics in model-written free text (rationales, drafts, explanations). */
public final class ProtectedAttributes {
    private static final Pattern TERMS = Pattern.compile(
            "(?i)\\b(?:gender|sex|male|female|man|woman|men|women|boy|girl|husband|wife|pregnan\\w*|maternity|paternity"
                    + "|age|aged|young|younger|elderly|old\\s+(?:man|woman|age)|(?:\\d{2}|[a-z]+)[- ]years?[- ]old|born|birth\\w*"
                    + "|millennial|gen\\s*z|boomer|religio\\w*|hindu|muslim|christian|sikh|buddhist|jain|jewish|church"
                    + "|mosque|caste|brahmin|dalit|racial|racism|race\\s+(?:and|or)|ethnic\\w*|nationality|marital|married|unmarried|divorced"
                    + "|single\\s+(?:man|woman|mother|father|parent|status)|disabled|disability|handicapped|wheelchair"
                    + "|gay|lesbian|lgbt\\w*|transgender|sexual\\s+orientation)\\b");

    private ProtectedAttributes() {}

    /** The protected-attribute terms found in the text, lower-cased. */
    public static List<String> scan(String... texts) {
        var found = new ArrayList<String>();
        for (var text : texts) {
            if (text == null) continue;
            var matcher = TERMS.matcher(text);
            while (matcher.find()) found.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        }
        return found;
    }

    public static boolean clean(String... texts) {
        return scan(texts).isEmpty();
    }

    /** Removes protected-attribute words from a search query so they cannot reach filters or embeddings. */
    public static String strip(String query) {
        return TERMS.matcher(query).replaceAll("").replaceAll("\\s{2,}", " ").strip();
    }
}
