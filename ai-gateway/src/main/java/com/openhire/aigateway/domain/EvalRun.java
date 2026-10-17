package com.openhire.aigateway.domain;

import com.openhire.aigateway.domain.AiTypes.RunStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** The outcome of running the golden dataset against one prompt version and one model. */
@Document("ai_eval_runs")
@CompoundIndex(name = "tenant_prompt_started", def = "{'tenantId': 1, 'promptId': 1, 'startedAt': -1}")
public record EvalRun(
        @Id String docId,
        String tenantId,
        String promptId,
        String baseline,
        String model,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        List<Check> checks,
        boolean passed,
        int casesRun,
        int casesTotal,
        List<CaseResult> cases,
        String error) {

    /** What the model did on one golden case. */
    public record CaseResult(
            String caseId,
            boolean valid,
            Integer score,
            int min,
            int max,
            boolean inBand,
            int quotesTotal,
            int quotesGrounded,
            long latencyMs) {}

    /** One gate: the measured value, the baseline's value when known, the rule and whether it held. */
    public record Check(String name, Double baseline, double candidate, String gate, boolean passed) {}

    public EvalRun finished(List<Check> results, Instant now) {
        boolean ok = results.stream().allMatch(Check::passed);
        return new EvalRun(
                docId,
                tenantId,
                promptId,
                baseline,
                model,
                RunStatus.DONE,
                startedAt,
                now,
                results,
                ok,
                casesTotal,
                casesTotal,
                cases,
                null);
    }

    public EvalRun failed(String reason, Instant now) {
        return new EvalRun(
                docId,
                tenantId,
                promptId,
                baseline,
                model,
                RunStatus.FAILED,
                startedAt,
                now,
                checks == null ? List.of() : checks,
                false,
                casesRun,
                casesTotal,
                cases,
                reason);
    }

    public EvalRun progress(List<CaseResult> done) {
        return new EvalRun(
                docId,
                tenantId,
                promptId,
                baseline,
                model,
                status,
                startedAt,
                finishedAt,
                checks,
                passed,
                done.size(),
                casesTotal,
                List.copyOf(done),
                error);
    }

    public String id() {
        return docId.substring(docId.lastIndexOf(':') + 1);
    }
}
