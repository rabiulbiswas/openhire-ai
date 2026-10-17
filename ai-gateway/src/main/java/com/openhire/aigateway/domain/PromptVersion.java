package com.openhire.aigateway.domain;

import com.openhire.aigateway.domain.AiTypes.EvalStatus;
import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** One version of a prompt: system and user templates ({{name}} fields) plus the JSON schema of the reply. */
@Document("ai_prompts")
@CompoundIndex(name = "tenant_key_version", def = "{'tenantId': 1, 'key': 1, 'version': 1}", unique = true)
public record PromptVersion(
        @Id String docId,
        String tenantId,
        String key,
        int version,
        PromptStatus status,
        int trafficPct,
        String owner,
        String systemTemplate,
        String userTemplate,
        String outputSchema,
        EvalStatus evalStatus,
        String evalRunId,
        Instant updatedAt) {
    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public static String docId(String tenantId, String key, int version) {
        return tenantId + ":" + key + "@" + version;
    }

    /** Public identifier, unique within a tenant. */
    public String id() {
        return key + "@" + version;
    }

    public Map<String, Object> schema() {
        return Json.readMap(outputSchema);
    }

    public PromptVersion withStatus(PromptStatus next, int pct, Instant now) {
        return new PromptVersion(
                docId,
                tenantId,
                key,
                version,
                next,
                pct,
                owner,
                systemTemplate,
                userTemplate,
                outputSchema,
                evalStatus,
                evalRunId,
                now);
    }

    public PromptVersion withEval(EvalStatus next, String runId, Instant now) {
        return new PromptVersion(
                docId,
                tenantId,
                key,
                version,
                status,
                trafficPct,
                owner,
                systemTemplate,
                userTemplate,
                outputSchema,
                next,
                runId,
                now);
    }

    public String renderSystem(Map<String, String> values) {
        return render(systemTemplate, values);
    }

    public String renderUser(Map<String, String> values) {
        return render(userTemplate, values);
    }

    static String render(String template, Map<String, String> values) {
        var matcher = FIELD.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            var value = values.get(matcher.group(1));
            if (value == null)
                throw new IllegalArgumentException("Prompt field {{" + matcher.group(1) + "}} has no value");
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
