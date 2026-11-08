package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.*;
import com.openhire.aigateway.domain.AiTypes.GuardRejection;
import java.util.*;
import org.springframework.stereotype.Service;

/** One governed AI task for interview preparation and evidence-linked drafting; never a decision maker. */
@Service
public class InterviewAiTask {
    public record Source(String sourceId, String text) {}

    public record Request(String mode, String context, List<Source> sources, String candidateName) {}

    private final ModelGateway gateway;

    public InterviewAiTask(ModelGateway gateway) {
        this.gateway = gateway;
    }

    public Map<String, Object> generate(String tenant, Request request) {
        if (request == null
                || !Set.of("QUESTIONS", "FEEDBACK", "DEBRIEF").contains(request.mode() == null ? "" : request.mode()))
            throw new IllegalArgumentException("Invalid interview assistant mode");
        if (request.context() == null || request.context().length() > 16000)
            throw new IllegalArgumentException("Interview context is required (max 16000 characters)");
        var sources = request.sources() == null ? List.<Source>of() : request.sources();
        if (sources.size() > 20) throw new IllegalArgumentException("At most 20 evidence sources");
        var safe = new LinkedHashMap<String, String>();
        for (var source : sources) {
            if (source == null
                    || source.sourceId() == null
                    || source.sourceId().isBlank()
                    || source.sourceId().length() > 100
                    || source.text() == null
                    || source.text().isBlank()
                    || source.text().length() > 6000
                    || safe.containsKey(source.sourceId()))
                throw new IllegalArgumentException("Invalid interview evidence source");
            safe.put(
                    source.sourceId(),
                    InputGuard.redact(source.text(), request.candidateName()).text());
        }
        if (!request.mode().equals("QUESTIONS") && safe.isEmpty())
            throw new IllegalArgumentException("Interview evidence is required");
        String context = InputGuard.neutralizeDelimiters(
                InputGuard.redact(request.context(), request.candidateName()).text());
        var result = gateway.chat(new ModelGateway.Call<Map<String, Object>>(
                tenant,
                AiTask.INTERVIEW_ASSIST,
                null,
                ModelGateway.keyOf(request.mode(), context, safe),
                Map.of(
                        "mode",
                        request.mode(),
                        "context",
                        context,
                        "sources",
                        InputGuard.neutralizeDelimiters(Json.write(safe))),
                1100,
                false,
                json -> guard(json, request.mode(), safe),
                out -> Map.of("humanReviewRequired", true, "evidenceLinked", true)));
        var response = new LinkedHashMap<>(result.value());
        response.put("provenance", result.provenance());
        return response;
    }

    static Map<String, Object> guard(Map<String, Object> json, String mode, Map<String, String> sources) {
        var questions = Json.list(json.get("questions"));
        var statements = Json.list(json.get("statements"));
        if (mode.equals("QUESTIONS")) {
            if (questions.isEmpty() || questions.size() > 5 || !statements.isEmpty())
                throw new GuardRejection("Return only interview questions");
            return Map.of(
                    "questions",
                    questions.stream()
                            .map(value -> {
                                var question = Json.map(value);
                                String text = checked(question.get("question"), 700);
                                String follow = Json.string(question.get("followUp"));
                                if (follow.length() > 400 || !ProtectedAttributes.clean(text, follow))
                                    throw new GuardRejection("Use only job-related questions");
                                return Map.of("question", text, "followUp", follow);
                            })
                            .toList(),
                    "statements",
                    List.of());
        }
        if (!questions.isEmpty() || statements.size() > 6)
            throw new GuardRejection("Return evidence-linked feedback only");
        return Map.of(
                "questions",
                List.of(),
                "statements",
                statements.stream()
                        .map(value -> {
                            var statement = Json.map(value);
                            String text = checked(statement.get("text"), 700),
                                    quote = checked(statement.get("quote"), 300);
                            String source = Json.string(statement.get("sourceId"));
                            if (!sources.containsKey(source)
                                    || !sources.get(source).contains(quote))
                                throw new GuardRejection("Every statement must quote its evidence source exactly");
                            if (!ProtectedAttributes.clean(text, quote))
                                throw new GuardRejection("Use only job-related feedback");
                            return Map.of("text", text, "quote", quote, "sourceId", source);
                        })
                        .toList());
    }

    private static String checked(Object value, int max) {
        String text = Json.string(value);
        if (text.isBlank() || text.length() > max) throw new GuardRejection("Invalid interview assistant output");
        return text;
    }
}
