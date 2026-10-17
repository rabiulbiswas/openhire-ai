package com.openhire.aiworkers.matching.application;

import com.openhire.aiworkers.matching.application.port.MatchingPorts.AiGateway;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.AssessmentStore;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.ChunkStore;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.ChunkText;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.CriterionVectors;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.RetryJob;
import com.openhire.aiworkers.matching.application.port.MatchingPorts.RetryStore;
import com.openhire.aiworkers.matching.domain.Assessment;
import com.openhire.aiworkers.matching.domain.Calibration;
import com.openhire.aiworkers.matching.domain.Chunk;
import com.openhire.aiworkers.matching.domain.Chunker;
import com.openhire.aiworkers.matching.domain.Hashes;
import com.openhire.aiworkers.matching.domain.Judgement;
import com.openhire.aiworkers.matching.domain.Lexical;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.aiworkers.matching.domain.MatchingRequest.Criterion;
import com.openhire.aiworkers.matching.domain.Retrieval;
import com.openhire.aiworkers.matching.domain.StoredChunk;
import com.openhire.aiworkers.shared.application.port.ResumeSource;
import com.openhire.aiworkers.shared.application.port.ResumeSource.Resume;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The five-stage matching orchestrator. AI output is a recommendation only: this class never touches an application
 * and a model outage degrades to a lexical PARTIAL assessment that is retried until a COMPLETE one supersedes it.
 */
public class MatchingService {
    private static final Logger LOG = LoggerFactory.getLogger(MatchingService.class);
    static final int EMBED_BATCH = 32;
    static final int TOP_K = 5;
    private static final Judgement.Provenance LEXICAL = new Judgement.Provenance("lexical-taxonomy-v1", "", "0", "");

    private final AiGateway gateway;
    private final ResumeSource resumes;
    private final ChunkStore chunkStore;
    private final CriterionVectors criterionVectors;
    private final AssessmentStore assessments;
    private final RetryStore retries;
    private final MatchingSettings settings;
    private final Clock clock;
    private final Chunker chunker = new Chunker();
    private final Lexical lexical;
    private final Semaphore permits;
    private final String owner = UUID.randomUUID().toString();

    public MatchingService(
            AiGateway gateway,
            ResumeSource resumes,
            ChunkStore chunkStore,
            CriterionVectors criterionVectors,
            AssessmentStore assessments,
            RetryStore retries,
            Lexical lexical,
            MatchingSettings settings,
            Clock clock) {
        this.gateway = gateway;
        this.resumes = resumes;
        this.chunkStore = chunkStore;
        this.criterionVectors = criterionVectors;
        this.assessments = assessments;
        this.retries = retries;
        this.lexical = lexical;
        this.settings = settings;
        this.clock = clock;
        this.permits = new Semaphore(Math.max(1, settings.concurrency()), true);
    }

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the service can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    /** Handles one command; a redelivery or a repeat of finished work does nothing. */
    public void handle(MatchingRequest request) {
        // A late command for an erased candidate must not create an assessment or chunks again.
        if (tombstones != null && tombstones.isErased(request.tenantId(), request.candidateId())) return;
        if (assessments.commandProcessed(request.tenantId(), request.eventId())) return;
        if (complete(request)) return;
        var resume = resumes.fetch(request.tenantId(), request.resumeId());
        if (!resume.ready()) throw new IllegalStateException("Resume " + request.resumeId() + " is not processed yet");
        var failed = request.failedKnockouts();
        if (!failed.isEmpty()) {
            save(notEligible(request, failed));
            return;
        }
        var coverage = coverage(request, resume);
        Assessment result;
        try {
            result = semantic(request, resume, coverage);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn(
                    "AI chain unavailable for application {} ({}); recording a partial assessment",
                    request.applicationId(),
                    failure.getClass().getSimpleName());
            save(partial(request, coverage));
            var now = clock.instant();
            retries.schedule(retryId(request), request, now, now.plus(settings.delay(0)));
            return;
        }
        save(result);
    }

    /** Re-runs due retries. Safe on every instance: each retry is claimed with a lease. */
    public int runDueRetries(int max) {
        int processed = 0;
        for (; processed < max; processed++) {
            var job = retries.claimNext(clock.instant(), settings.lease(), owner);
            if (job.isEmpty()) break;
            retry(job.get());
        }
        return processed;
    }

    private void retry(RetryJob job) {
        var request = job.request();
        try {
            if (complete(request)) {
                retries.finish(job.id(), "DONE");
                return;
            }
            var resume = resumes.fetch(request.tenantId(), request.resumeId());
            if (!resume.ready()) throw new IllegalStateException("Resume is not processed yet");
            var result = semantic(request, resume, coverage(request, resume));
            save(result);
            retries.finish(job.id(), "DONE");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            int failures = job.attempt() + 1;
            var now = clock.instant();
            if (now.isAfter(job.firstFailedAt().plus(settings.retryWindow()))) {
                LOG.warn("Giving up re-scoring application {}", request.applicationId());
                retries.finish(job.id(), "EXHAUSTED");
            } else retries.reschedule(job.id(), failures, now.plus(settings.delay(failures)));
        }
    }

    private boolean complete(MatchingRequest request) {
        return assessments.hasComplete(
                request.tenantId(),
                request.applicationId(),
                request.resumeId(),
                request.jobVersion(),
                request.rubricHash());
    }

    private List<Double> coverage(MatchingRequest request, Resume resume) {
        return request.rubric().stream()
                .map(c -> lexical.coverage(c, request.mustHaveSkills(), resume.text()))
                .toList();
    }

    /** Stages 3 to 5. Throws when any gateway call fails. */
    private Assessment semantic(MatchingRequest request, Resume resume, List<Double> coverage) throws Exception {
        permits.acquire();
        try {
            var chunks = chunker.chunk(request.resumeId(), resume.text());
            var stored = embedChunks(request, chunks);
            var vectors = criterionVectors(request);
            var inputs = new ArrayList<Calibration.Input>();
            var provenance = new ArrayList<Judgement.Provenance>();
            boolean hallucinated = false;
            for (int i = 0; i < request.rubric().size(); i++) {
                var criterion = request.rubric().get(i);
                var hits = Retrieval.topK(vectors.get(i), stored, TOP_K);
                var shown = new LinkedHashMap<String, String>();
                hits.forEach(hit ->
                        shown.put(hit.chunk().chunk().id(), hit.chunk().chunk().text()));
                var judgement = gateway.judge(
                        request.tenantId(),
                        criterion,
                        shown.entrySet().stream()
                                .map(e -> new ChunkText(e.getKey(), e.getValue()))
                                .toList(),
                        String.valueOf(request.jobVersion()));
                var quotes = judgement.verifiedQuotes(shown);
                hallucinated |= judgement.discardedQuotes(shown);
                provenance.add(judgement.provenance());
                inputs.add(new Calibration.Input(
                        criterion, coverage.get(i), judgement.score(), !quotes.isEmpty(), quotes));
            }
            var sections = chunks.stream().map(Chunk::section).collect(Collectors.toSet());
            int words = resume.text().trim().split("\\s+").length;
            var result = Calibration.calibrate(inputs, Calibration.completeness(words, sections));
            // Status rule: PARTIAL only when the AI could not do its job (a gateway failure never reaches here) or the
            // model cited quotes that grounding discarded. A model that honestly finds no evidence is a COMPLETE,
            // low-scoring assessment (ungrounded criteria score 0.5 * lexical).
            return build(
                    request,
                    hallucinated ? Assessment.PARTIAL : Assessment.COMPLETE,
                    result,
                    provenance.isEmpty() ? LEXICAL : provenance.get(0),
                    hallucinated ? Assessment.PARTIAL_LABEL : Assessment.REVIEW_LABEL,
                    List.of());
        } finally {
            permits.release();
        }
    }

    private List<StoredChunk> embedChunks(MatchingRequest request, List<Chunk> chunks) {
        var ids = chunks.stream().map(Chunk::id).toList();
        if (ids.equals(chunkStore.chunkIds(request.tenantId(), request.resumeId()))
                && !chunkStore
                        .chunks(request.tenantId(), request.candidateId(), request.resumeId())
                        .isEmpty())
            return chunkStore.chunks(request.tenantId(), request.candidateId(), request.resumeId());
        var vectors = embed(request.tenantId(), chunks.stream().map(Chunk::text).toList());
        var stored = new ArrayList<StoredChunk>();
        for (int i = 0; i < chunks.size(); i++) stored.add(new StoredChunk(chunks.get(i), vectors.get(i)));
        chunkStore.replace(
                request.tenantId(), request.candidateId(), request.applicationId(), request.resumeId(), stored);
        return stored;
    }

    private List<double[]> criterionVectors(MatchingRequest request) {
        var texts =
                request.rubric().stream().map(MatchingService::criterionText).toList();
        var vectors = new double[texts.size()][];
        var missing = new ArrayList<Integer>();
        for (int i = 0; i < texts.size(); i++) {
            Optional<double[]> cached = criterionVectors.find(request.tenantId(), Hashes.sha256(texts.get(i)));
            if (cached.isPresent()) vectors[i] = cached.get();
            else missing.add(i);
        }
        if (!missing.isEmpty()) {
            var fresh =
                    embed(request.tenantId(), missing.stream().map(texts::get).toList());
            for (int j = 0; j < missing.size(); j++) {
                int i = missing.get(j);
                vectors[i] = fresh.get(j);
                criterionVectors.put(request.tenantId(), Hashes.sha256(texts.get(i)), fresh.get(j));
            }
        }
        return List.of(vectors);
    }

    static String criterionText(Criterion criterion) {
        return criterion.description().isBlank()
                ? criterion.label()
                : criterion.label() + ": " + criterion.description();
    }

    private List<double[]> embed(String tenantId, List<String> inputs) {
        var all = new ArrayList<double[]>();
        for (int from = 0; from < inputs.size(); from += EMBED_BATCH) {
            var batch = inputs.subList(from, Math.min(inputs.size(), from + EMBED_BATCH));
            var vectors = gateway.embed(tenantId, batch);
            if (vectors.size() != batch.size()) throw new IllegalStateException("Embedding count mismatch");
            all.addAll(vectors);
        }
        return all;
    }

    private Assessment partial(MatchingRequest request, List<Double> coverage) {
        return build(
                request,
                Assessment.PARTIAL,
                Calibration.lexicalOnly(request.rubric(), coverage),
                LEXICAL,
                Assessment.PARTIAL_LABEL,
                List.of());
    }

    private Assessment notEligible(MatchingRequest request, List<String> failed) {
        var empty = Calibration.lexicalOnly(List.of(), List.of());
        return build(request, Assessment.NOT_ELIGIBLE, empty, LEXICAL, Assessment.REVIEW_LABEL, failed);
    }

    private Assessment build(
            MatchingRequest request,
            String status,
            Calibration.Result result,
            Judgement.Provenance provenance,
            String label,
            List<String> failedRules) {
        return new Assessment(
                "asm_" + UUID.randomUUID().toString().replace("-", ""),
                request.tenantId(),
                request.applicationId(),
                request.candidateId(),
                request.resumeId(),
                request.jobId(),
                request.jobVersion(),
                request.rubricHash(),
                status,
                result.score(),
                result.confidence(),
                result.criteria(),
                result.strengths(),
                result.gapsToProbe(),
                result.hardGaps(),
                failedRules,
                provenance,
                label,
                request.eventId(),
                clock.instant());
    }

    private void save(Assessment assessment) {
        assessments.save(assessment, AssessmentEvents.completed(assessment));
    }

    static String retryId(MatchingRequest request) {
        return Hashes.sha256(
                request.tenantId(),
                request.applicationId(),
                request.resumeId(),
                String.valueOf(request.jobVersion()),
                request.rubricHash());
    }
}
