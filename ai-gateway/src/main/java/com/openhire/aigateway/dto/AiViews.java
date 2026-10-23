package com.openhire.aigateway.dto;

import com.openhire.aigateway.domain.AiTypes.Provenance;
import java.util.List;
import java.util.Map;

/** Request and response shapes of the internal AI endpoints. */
public final class AiViews {
    private AiViews() {}

    public record ChunkIn(String chunkId, String text) {}

    public record CriterionIn(String id, String label, String requirement, String description) {}

    public record EmbedRequest(List<String> inputs, String inputType) {}

    public record EmbedResponse(List<float[]> vectors, int dimensions, Provenance provenance) {}

    public record JudgeRequest(
            CriterionIn criterion, List<ChunkIn> chunks, String rubricVersion, String candidateName) {}

    public record EvidenceView(String chunkId, String quote) {}

    public record GuardrailsView(boolean grounded, boolean protectedAttributeFree, boolean injectionSuspected) {}

    public record JudgeResponse(
            String criterionId,
            int score,
            List<EvidenceView> evidence,
            List<String> strengths,
            List<String> gaps,
            String rationale,
            boolean insufficientEvidence,
            GuardrailsView guardrails,
            Provenance provenance) {}

    public record StructureRequest(String text, String candidateName) {}

    public record JdRequest(
            String title, String team, String level, String location, List<String> mustHaveSkills, String tone) {}

    public record ThreadItem(String from, String text) {}

    public record MessageRequest(
            String purpose,
            String candidateFirstName,
            String jobTitle,
            List<ThreadItem> thread,
            String notes,
            String channel) {}

    public record MessageResponse(String text, Provenance provenance) {}

    public record NlRequest(String query) {}

    public record JobIn(String title, String summary, List<String> criteria) {}

    public record ExplainRequest(JobIn job, List<ChunkIn> historical, List<ChunkIn> current) {}

    public record StatementView(String statement, String source, String chunkId, String quote) {}

    public record ExplainResponse(
            String whyFitNow,
            String historicalEvidence,
            List<StatementView> whyFitNowStatements,
            List<StatementView> historicalStatements,
            Provenance provenance) {}

    /** Staff drafting request and the response the console's AiJobDraft type expects, plus provenance. */
    public record DraftResponse(
            String description,
            List<String> biasFlags,
            List<Map<String, Object>> biasFlagDetails,
            List<Map<String, Object>> suggestedInterviewPlan,
            List<Map<String, Object>> rubric,
            List<Map<String, Object>> knockoutQuestions,
            Provenance provenance) {}
}
