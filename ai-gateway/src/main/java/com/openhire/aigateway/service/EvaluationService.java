package com.openhire.aigateway.service;

import com.openhire.aigateway.domain.AiTask;
import com.openhire.aigateway.domain.AiTypes.EvalStatus;
import com.openhire.aigateway.domain.AiTypes.PromptStatus;
import com.openhire.aigateway.domain.AiTypes.RunStatus;
import com.openhire.aigateway.domain.EvalRun;
import com.openhire.aigateway.domain.EvalRun.CaseResult;
import com.openhire.aigateway.domain.EvalRun.Check;
import com.openhire.aigateway.domain.Golden;
import com.openhire.aigateway.domain.PromptVersion;
import com.openhire.aigateway.domain.SeedPrompts;
import com.openhire.aigateway.dto.AiViews.ChunkIn;
import com.openhire.aigateway.dto.AiViews.CriterionIn;
import com.openhire.aigateway.dto.AiViews.JudgeRequest;
import com.openhire.aigateway.exception.AiExceptions.BudgetExhausted;
import com.openhire.aigateway.exception.AiExceptions.EvaluationBusy;
import com.openhire.aigateway.service.port.AiPorts.EvalStore;
import com.openhire.aigateway.service.port.AiPorts.GoldenDatasets;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the golden dataset against one prompt version, case by case (the model serves one request at a time), and
 * records the gate results. One run per tenant at a time; runs execute on a bounded executor.
 */
public class EvaluationService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final Duration STALE = Duration.ofMinutes(30);

    private final PromptRegistry prompts;
    private final AiTaskService tasks;
    private final TaskRouter router;
    private final EvalStore evals;
    private final GoldenDatasets datasets;
    private final EvalGates gates;
    private final Executor executor;
    private final Clock clock;
    private final AuditRecorder audit;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public EvaluationService(
            PromptRegistry prompts,
            AiTaskService tasks,
            TaskRouter router,
            EvalStore evals,
            GoldenDatasets datasets,
            EvalGates gates,
            Executor executor,
            Clock clock,
            AuditRecorder audit) {
        this.prompts = prompts;
        this.tasks = tasks;
        this.router = router;
        this.evals = evals;
        this.datasets = datasets;
        this.gates = gates;
        this.executor = executor;
        this.clock = clock;
        this.audit = audit;
    }

    /** Starts a run and returns it in status RUNNING; poll {@link #get}. */
    public EvalRun start(String tenantId, String promptId, String actor) {
        var prompt = prompts.get(tenantId, promptId);
        if (!prompt.key().equals(SeedPrompts.JUDGE))
            throw new IllegalArgumentException("The golden dataset evaluates " + SeedPrompts.JUDGE + " versions only");
        if (!active.add(tenantId)
                || evals.running(tenantId, clock.instant().minus(STALE)).isPresent()) {
            active.remove(tenantId);
            throw new EvaluationBusy("An evaluation is already running for this organization");
        }
        EvalRun run;
        try {
            var dataset = datasets.load();
            var model = router.routes(tenantId, AiTask.MATCH_JUDGE).get(0).model();
            var baseline = prompts.versions(tenantId, prompt.key()).stream()
                    .filter(p -> p.status() == PromptStatus.ACTIVE && p.version() != prompt.version())
                    .max(Comparator.comparingInt(PromptVersion::version))
                    .map(PromptVersion::id)
                    .orElse(null);
            run = evals.save(new EvalRun(
                    tenantId + ":" + UUID.randomUUID(),
                    tenantId,
                    prompt.id(),
                    baseline,
                    model,
                    RunStatus.RUNNING,
                    clock.instant(),
                    null,
                    List.of(),
                    false,
                    0,
                    dataset.cases().size(),
                    List.of(),
                    null));
            prompts.save(prompt.withEval(EvalStatus.NONE, run.id(), clock.instant()));
            var started = run;
            executor.execute(() -> execute(tenantId, prompt, started, dataset));
        } catch (RejectedExecutionException busy) {
            active.remove(tenantId);
            throw new EvaluationBusy("Evaluation capacity is busy; try again shortly");
        } catch (RuntimeException e) {
            active.remove(tenantId);
            throw e;
        }
        audit.record(AuditRecord.of(tenantId, "EVALUATION_RUN_STARTED")
                .user(actor, null)
                .resource("EvalRun", run.id())
                .detail("promptId", promptId)
                .detail("model", run.model())
                .detail("cases", run.casesTotal())
                .build());
        return run;
    }

    public EvalRun get(String tenantId, String id) {
        return evals.find(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Evaluation run " + id + " was not found"));
    }

    void execute(String tenantId, PromptVersion prompt, EvalRun run, Golden.Dataset dataset) {
        try {
            var results = new ArrayList<CaseResult>();
            var metrics = new Metrics();
            for (var testCase : dataset.cases()) {
                results.add(runCase(tenantId, prompt, testCase, metrics));
                run = evals.save(run.progress(results));
            }
            var baselineChecks = baselineValues(tenantId, run.baseline());
            var checks = gate(metrics, gates, baselineChecks);
            var done = evals.save(run.finished(checks, clock.instant()));
            prompts.save(prompts.get(tenantId, prompt.id())
                    .withEval(done.passed() ? EvalStatus.PASSED : EvalStatus.FAILED, done.id(), clock.instant()));
        } catch (RuntimeException e) {
            log.warn("Evaluation {} failed ({})", run.id(), e.getClass().getSimpleName());
            evals.save(run.failed(
                    e instanceof BudgetExhausted ? "The AI budget is exhausted" : "The evaluation could not complete",
                    clock.instant()));
            prompts.save(prompts.get(tenantId, prompt.id()).withEval(EvalStatus.FAILED, run.id(), clock.instant()));
        } finally {
            active.remove(tenantId);
        }
    }

    private Map<String, Double> baselineValues(String tenantId, String baselinePromptId) {
        if (baselinePromptId == null) return Map.of();
        var runId = prompts.get(tenantId, baselinePromptId).evalRunId();
        if (runId == null) return Map.of();
        var values = new HashMap<String, Double>();
        evals.find(tenantId, runId).filter(r -> r.status() == RunStatus.DONE).ifPresent(r -> r.checks()
                .forEach(c -> values.put(c.name(), c.candidate())));
        return values;
    }

    private CaseResult runCase(String tenantId, PromptVersion prompt, Golden.Case c, Metrics metrics) {
        var request = new JudgeRequest(
                new CriterionIn(
                        c.criterion().id(),
                        c.criterion().label(),
                        c.criterion().requirement(),
                        c.criterion().description()),
                c.chunks().stream().map(x -> new ChunkIn(x.chunkId(), x.text())).toList(),
                "eval",
                c.candidateName());
        try {
            var run = tasks.judgeWith(tenantId, request, prompt, true);
            var out = run.output();
            boolean inBand = out.score() >= c.min() && out.score() <= c.max();
            long latency = run.response().provenance().latencyMs();
            metrics.record(
                    c,
                    true,
                    out.score(),
                    out.quotesTotal(),
                    out.quotesGrounded(),
                    latency,
                    run.response().provenance().costInr());
            return new CaseResult(
                    c.id(),
                    true,
                    out.score(),
                    c.min(),
                    c.max(),
                    inBand,
                    out.quotesTotal(),
                    out.quotesGrounded(),
                    latency);
        } catch (BudgetExhausted e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.record(c, false, 0, 0, 0, 0, 0);
            return new CaseResult(c.id(), false, null, c.min(), c.max(), false, 0, 0, 0);
        }
    }

    /** Raw numbers collected over the dataset. */
    static final class Metrics {
        int cases;
        int valid;
        int inBand;
        int quotes;
        int grounded;
        int injectionCases;
        int injectionResisted;
        final List<Long> latencies = new ArrayList<>();
        double cost;
        final Map<String, Integer> pairScore = new HashMap<>();
        final List<Double> pairDeltas = new ArrayList<>();

        void record(
                Golden.Case c,
                boolean ok,
                int score,
                int quotesTotal,
                int quotesGrounded,
                long latency,
                double costInr) {
            cases++;
            if (ok) {
                valid++;
                latencies.add(latency);
                cost += costInr;
                quotes += quotesTotal;
                grounded += quotesGrounded;
                if (score >= c.min() && score <= c.max()) inBand++;
            }
            if ("INJECTION".equals(c.trap())) {
                injectionCases++;
                if (ok && score <= c.max()) injectionResisted++;
            }
            if (c.pairId() != null) {
                var key = c.pairId();
                var other = pairScore.remove(key);
                if (other == null) pairScore.put(key, ok ? score : -1);
                else if (other < 0 || !ok) pairDeltas.add(5.0);
                else pairDeltas.add((double) Math.abs(other - score));
            }
        }
    }

    /** Turns raw metrics into the gate checks. */
    static List<Check> gate(Metrics m, EvalGates g, Map<String, Double> baseline) {
        var checks = new ArrayList<Check>();
        double n = Math.max(1, m.cases);
        add(
                checks,
                baseline,
                "schemaValidityPct",
                m.valid / n * 100,
                "at least " + num(g.schemaValidityPct()) + "%",
                m.valid / n * 100 >= g.schemaValidityPct());
        double groundedPct = m.quotes == 0 ? 100 : m.grounded * 100.0 / m.quotes;
        add(
                checks,
                baseline,
                "groundedEvidencePct",
                groundedPct,
                "at least " + num(g.groundedEvidencePct()) + "%",
                groundedPct >= g.groundedEvidencePct());
        double band = m.inBand / n * 100;
        add(
                checks,
                baseline,
                "scoreInBandPct",
                band,
                "at least " + num(g.scoreInBandPct()) + "%",
                band >= g.scoreInBandPct());
        double p95delta = m.pairDeltas.isEmpty() ? 0 : percentile95(m.pairDeltas);
        add(
                checks,
                baseline,
                "counterfactualDeltaP95",
                p95delta,
                "at most " + num(g.counterfactualDeltaP95()) + " point",
                p95delta <= g.counterfactualDeltaP95());
        double inj = m.injectionCases == 0 ? 100 : m.injectionResisted * 100.0 / m.injectionCases;
        add(
                checks,
                baseline,
                "injectionResistancePct",
                inj,
                "at least " + num(g.injectionResistancePct()) + "%",
                inj >= g.injectionResistancePct());
        double p95 = m.latencies.isEmpty()
                ? 0
                : percentile95(m.latencies.stream().map(Long::doubleValue).toList());
        add(
                checks,
                baseline,
                "p95LatencyMs",
                p95,
                "under " + num(g.p95LatencyMs() / 1000) + " s",
                p95 < g.p95LatencyMs());
        double perAssessment = m.valid == 0 ? 0 : m.cost / m.valid * g.criteriaPerAssessment();
        add(
                checks,
                baseline,
                "costPerAssessmentInr",
                perAssessment,
                "under INR " + num(g.costPerAssessmentInr()),
                perAssessment <= g.costPerAssessmentInr());
        return checks;
    }

    private static void add(
            List<Check> checks, Map<String, Double> baseline, String name, double value, String gate, boolean passed) {
        checks.add(new Check(name, baseline.get(name), Math.round(value * 100) / 100.0, gate, passed));
    }

    /** Nearest-rank 95th percentile. */
    static double percentile95(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private static String num(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
