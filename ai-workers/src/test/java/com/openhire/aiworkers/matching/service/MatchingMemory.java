package com.openhire.aiworkers.matching.service;

import com.openhire.aiworkers.matching.domain.Assessment;
import com.openhire.aiworkers.matching.domain.Judgement;
import com.openhire.aiworkers.matching.domain.MatchingRequest;
import com.openhire.aiworkers.matching.domain.StoredChunk;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AiGateway;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.AssessmentStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.ChunkStore;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.ChunkText;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.CriterionVectors;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.RetryJob;
import com.openhire.aiworkers.matching.service.port.MatchingPorts.RetryStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory ports and a scripted gateway shared by the matching tests. */
final class MatchingMemory {
    private MatchingMemory() {}

    static final List<String> DIMENSIONS = List.of("java", "kafka", "spring", "sql", "payments");

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    /** Embeds text as keyword counts and judges by looking for the criterion label in the chunks shown. */
    static final class FakeGateway implements AiGateway {
        volatile boolean down;
        volatile boolean failJudge;
        volatile long judgeDelayMillis;
        final Set<String> hallucinate = new HashSet<>();
        final AtomicInteger embedCalls = new AtomicInteger();
        final AtomicInteger judgeCalls = new AtomicInteger();
        final AtomicInteger maxBatch = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final List<List<ChunkText>> shown = new ArrayList<>();

        @Override
        public List<double[]> embed(String tenantId, List<String> inputs) {
            if (down) throw new IllegalStateException("AI_UNAVAILABLE");
            embedCalls.incrementAndGet();
            maxBatch.accumulateAndGet(inputs.size(), Math::max);
            return inputs.stream().map(FakeGateway::vector).toList();
        }

        static double[] vector(String text) {
            var lower = text.toLowerCase(Locale.ROOT);
            var vector = new double[DIMENSIONS.size()];
            for (int i = 0; i < vector.length; i++) {
                int from = 0;
                while ((from = lower.indexOf(DIMENSIONS.get(i), from)) >= 0) {
                    vector[i]++;
                    from += DIMENSIONS.get(i).length();
                }
            }
            return vector;
        }

        @Override
        public Judgement judge(
                String tenantId, MatchingRequest.Criterion criterion, List<ChunkText> chunks, String rubricVersion) {
            if (down || failJudge) throw new IllegalStateException("AI_UNAVAILABLE");
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            try {
                if (judgeDelayMillis > 0) Thread.sleep(judgeDelayMillis);
                judgeCalls.incrementAndGet();
                synchronized (shown) {
                    shown.add(chunks);
                }
                var provenance =
                        new Judgement.Provenance("qwen2.5:3b-instruct", "match.criterion.judge", "1", "trace-1");
                var label = criterion.label().toLowerCase(Locale.ROOT);
                if (hallucinate.contains(criterion.id()))
                    return new Judgement(
                            criterion.id(),
                            5,
                            List.of(new Judgement.Evidence(chunks.get(0).chunkId(), "an invented sentence")),
                            List.of(),
                            List.of(),
                            "",
                            false,
                            true,
                            provenance);
                for (var chunk : chunks) {
                    int at = chunk.text().toLowerCase(Locale.ROOT).indexOf(label);
                    if (at >= 0)
                        return new Judgement(
                                criterion.id(),
                                4,
                                List.of(new Judgement.Evidence(
                                        chunk.chunkId(), chunk.text().substring(at, at + label.length()))),
                                List.of("strong"),
                                List.of(),
                                "found",
                                false,
                                true,
                                provenance);
                }
                return new Judgement(
                        criterion.id(), 0, List.of(), List.of(), List.of("none"), "", true, true, provenance);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    static final class Chunks implements ChunkStore {
        final Map<String, List<StoredChunk>> byResume = new LinkedHashMap<>();
        final Map<String, String> candidateOf = new LinkedHashMap<>();

        public synchronized List<String> chunkIds(String tenantId, String resumeId) {
            return byResume.getOrDefault(tenantId + resumeId, List.of()).stream()
                    .map(c -> c.chunk().id())
                    .toList();
        }

        public synchronized void replace(
                String tenantId, String candidateId, String applicationId, String resumeId, List<StoredChunk> chunks) {
            byResume.put(tenantId + resumeId, List.copyOf(chunks));
            candidateOf.put(tenantId + resumeId, candidateId);
        }

        public synchronized List<StoredChunk> chunks(String tenantId, String candidateId, String resumeId) {
            return candidateId.equals(candidateOf.get(tenantId + resumeId))
                    ? byResume.getOrDefault(tenantId + resumeId, List.of())
                    : List.of();
        }
    }

    static final class Vectors implements CriterionVectors {
        final Map<String, double[]> cache = new LinkedHashMap<>();

        public synchronized Optional<double[]> find(String tenantId, String textHash) {
            return Optional.ofNullable(cache.get(tenantId + textHash));
        }

        public synchronized void put(String tenantId, String textHash, double[] vector) {
            cache.put(tenantId + textHash, vector);
        }
    }

    static final class Saved {
        final Assessment assessment;
        final Map<String, Object> event;
        boolean current = true;

        Saved(Assessment assessment, Map<String, Object> event) {
            this.assessment = assessment;
            this.event = event;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload() {
            return (Map<String, Object>) event.get("payload");
        }
    }

    static final class Assessments implements AssessmentStore {
        final List<Saved> saved = new ArrayList<>();

        public synchronized boolean commandProcessed(String tenantId, String eventId) {
            return saved.stream().anyMatch(s -> s.assessment.commandEventId().equals(eventId));
        }

        public synchronized boolean hasComplete(
                String tenantId, String applicationId, String resumeId, int jobVersion, String rubricHash) {
            return saved.stream()
                    .map(s -> s.assessment)
                    .anyMatch(a -> a.status().equals(Assessment.COMPLETE)
                            && a.applicationId().equals(applicationId)
                            && a.resumeId().equals(resumeId)
                            && a.jobVersion() == jobVersion
                            && a.rubricHash().equals(rubricHash));
        }

        public synchronized void save(Assessment assessment, Map<String, Object> event) {
            if (assessment.status().equals(Assessment.COMPLETE))
                saved.stream()
                        .filter(s -> s.assessment.applicationId().equals(assessment.applicationId()))
                        .forEach(s -> s.current = false);
            saved.add(new Saved(assessment, event));
        }

        synchronized List<Saved> versions() {
            return List.copyOf(saved);
        }
    }

    static final class Retries implements RetryStore {
        record Row(String id, MatchingRequest request, Instant first, int attempt, Instant next, String state) {}

        final Map<String, Row> rows = new LinkedHashMap<>();

        public synchronized void schedule(String id, MatchingRequest request, Instant first, Instant next) {
            rows.putIfAbsent(id, new Row(id, request, first, 0, next, "PENDING"));
        }

        public synchronized Optional<RetryJob> claimNext(Instant now, Duration lease, String owner) {
            return rows.values().stream()
                    .filter(r -> r.state.equals("PENDING") && !r.next.isAfter(now))
                    .findFirst()
                    .map(r -> {
                        rows.put(r.id, new Row(r.id, r.request, r.first, r.attempt, now.plus(lease), r.state));
                        return new RetryJob(r.id, r.request, r.attempt, r.first);
                    });
        }

        public synchronized void reschedule(String id, int attempt, Instant next) {
            var r = rows.get(id);
            rows.put(id, new Row(id, r.request, r.first, attempt, next, "PENDING"));
        }

        public synchronized void finish(String id, String state) {
            var r = rows.get(id);
            rows.put(id, new Row(id, r.request, r.first, r.attempt, r.next, state));
        }
    }
}
