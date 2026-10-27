package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.Json;
import com.openhire.aigateway.dto.AiViews.JdRequest;
import java.util.List;

/** The application owns headings and supplied facts; the model writes only the narrative sections. */
final class JobDraftDescription {
    private JobDraftDescription() {}

    static String render(Object value, JdRequest brief, List<String> required, List<String> optional) {
        // Preserve explicitly customized legacy prompt contracts.
        if (value instanceof String text) return text;
        var sections = Json.map(value);
        var out = new StringBuilder("About the job\nProject Role: ")
                .append(brief.title().strip())
                .append("\n\nProject Role Description: ")
                .append(Json.string(sections.get("roleDescription")).strip());
        if (!required.isEmpty()) out.append("\n\nMust-have skills: ").append(String.join(", ", required));
        if (!optional.isEmpty())
            out.append("\n\nGood-to-have skills (optional): ").append(String.join(", ", optional));
        if (brief.qualification() != null && !brief.qualification().isBlank())
            out.append("\n\nEducational Qualification: ")
                    .append(brief.qualification().strip());
        out.append("\n\nSummary\n").append(Json.string(sections.get("summary")).strip());
        out.append("\n\nRoles & Responsibilities\n");
        bullets(out, sections.get("responsibilities"));
        out.append("\nProfessional & Technical Skills\n");
        bullets(out, sections.get("technicalSkills"));
        out.append("\nAdditional Information\n");
        boolean additional = false;
        if (brief.location() != null && !brief.location().isBlank()) {
            out.append("- Location: ").append(brief.location().strip()).append('\n');
            additional = true;
        }
        if (brief.workMode() != null && !brief.workMode().isBlank()) {
            out.append("- Work mode: ").append(brief.workMode()).append('\n');
            additional = true;
        }
        if (brief.qualification() != null && !brief.qualification().isBlank()) {
            out.append("- Qualification: ")
                    .append(brief.qualification().strip())
                    .append('\n');
            additional = true;
        }
        if (!additional) out.append("No additional requirements were specified.\n");
        return out.toString().strip();
    }

    private static void bullets(StringBuilder out, Object values) {
        for (var value : Json.list(values))
            out.append("- ").append(Json.string(value).strip()).append('\n');
    }
}
