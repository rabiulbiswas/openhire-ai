package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.EvalStatus;
import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import com.openhire.aigateway.domain.AiTypes.RunStatus;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.domain.PromptVersion;
import com.openhire.aigateway.domain.SeedPrompts;
import com.openhire.aigateway.exception.AiExceptions.PromotionBlocked;
import com.openhire.aigateway.service.port.AiPorts.EvalStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Lists prompt versions and promotes one to ACTIVE, but only when its latest evaluation passed every gate. */
public class PromptAdminService {
    public record PromptSummary(
            String id,
            String key,
            int version,
            PromptStatus status,
            int trafficPct,
            String owner,
            EvalStatus evalStatus,
            String evalRunId,
            Instant updatedAt) {}

    public record PromptDetail(
            String id,
            String key,
            int version,
            PromptStatus status,
            int trafficPct,
            String owner,
            EvalStatus evalStatus,
            String evalRunId,
            Instant updatedAt,
            String systemTemplate,
            String userTemplate,
            Map<String, Object> outputSchema) {}

    private final PromptRegistry prompts;
    private final EvalStore evals;
    private final TaskRouter router;
    private final Clock clock;
    private final AuditRecorder audit;

    public PromptAdminService(
            PromptRegistry prompts, EvalStore evals, TaskRouter router, Clock clock, AuditRecorder audit) {
        this.prompts = prompts;
        this.evals = evals;
        this.router = router;
        this.clock = clock;
        this.audit = audit;
    }

    public List<PromptSummary> list(String tenantId) {
        return prompts.list(tenantId).stream()
                .sorted(Comparator.comparing(PromptVersion::key).thenComparing(PromptVersion::version))
                .map(PromptAdminService::summary)
                .toList();
    }

    public PromptDetail get(String tenantId, String id) {
        var p = prompts.get(tenantId, id);
        return new PromptDetail(
                p.id(),
                p.key(),
                p.version(),
                p.status(),
                p.trafficPct(),
                p.owner(),
                p.evalStatus(),
                p.evalRunId(),
                p.updatedAt(),
                p.systemTemplate(),
                p.userTemplate(),
                p.schema());
    }

    /**
     * Makes the version ACTIVE with {@code trafficPct} of requests (default 100). At 100 the previous ACTIVE versions
     * retire; below 100 they keep the remainder, which is a gradual rollout.
     */
    public PromptSummary promote(String tenantId, String id, Integer trafficPct, String actor) {
        var prompt = prompts.get(tenantId, id);
        int pct = trafficPct == null ? 100 : trafficPct;
        if (pct < 1 || pct > 100) throw new IllegalArgumentException("trafficPct must be between 1 and 100");
        if (prompt.status() == PromptStatus.ACTIVE && prompt.trafficPct() == pct) return summary(prompt);
        var run = prompt.evalRunId() == null
                ? null
                : evals.find(tenantId, prompt.evalRunId()).orElse(null);
        if (run == null)
            throw new PromotionBlocked(
                    "Version " + id + " has no evaluation run; evaluate it first (only " + SeedPrompts.JUDGE
                            + " versions have a golden dataset)",
                    List.of("evaluation"));
        if (run.status() != RunStatus.DONE)
            throw new PromotionBlocked("The latest evaluation of " + id + " is " + run.status(), List.of("evaluation"));
        var failed = run.checks().stream()
                .filter(c -> !c.passed())
                .map(EvalRun.Check::name)
                .toList();
        if (!failed.isEmpty())
            throw new PromotionBlocked(
                    "Version " + id + " failed evaluation gates: " + String.join(", ", failed), failed);
        var current = router.effective(tenantId).tasks().get(AiTask.MATCH_JUDGE.name());
        if (!current.model().equals(run.model()))
            throw new PromotionBlocked(
                    "Version " + id + " was evaluated on " + run.model() + " but match.judge now uses "
                            + current.model() + "; evaluate it again",
                    List.of("model"));
        var now = clock.instant();
        for (var other : prompts.versions(tenantId, prompt.key()))
            if (!other.docId().equals(prompt.docId()) && other.status() == PromptStatus.ACTIVE)
                prompts.save(
                        pct == 100
                                ? other.withStatus(PromptStatus.RETIRED, 0, now)
                                : other.withStatus(PromptStatus.ACTIVE, 100 - pct, now));
        var promoted = prompts.save(prompt.withStatus(PromptStatus.ACTIVE, pct, now));
        audit.record(AuditRecord.of(tenantId, "PROMPT_PROMOTED")
                .user(actor, null)
                .resource("PromptVersion", promoted.id())
                .detail("promptKey", promoted.key())
                .detail("version", promoted.version())
                .detail("trafficPct", pct)
                .detail("evalRunId", run.id())
                .build());
        return summary(promoted);
    }

    static PromptSummary summary(PromptVersion p) {
        return new PromptSummary(
                p.id(),
                p.key(),
                p.version(),
                p.status(),
                p.trafficPct(),
                p.owner(),
                p.evalStatus(),
                p.evalRunId(),
                p.updatedAt());
    }
}
