package com.openhire.analytics.service;

import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.DeadLetterRecord;
import com.openhire.analytics.domain.Facts;
import com.openhire.analytics.domain.Facts.AiDayFact;
import com.openhire.analytics.domain.Facts.AiSample;
import com.openhire.analytics.domain.Facts.AssessmentFact;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.DecisionKind;
import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyFact;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.Facts.OfferFact;
import com.openhire.analytics.domain.ServiceSample;
import com.openhire.analytics.service.port.AnalyticsPorts.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory ports for unit tests. */
final class Memory {
    private Memory() {}

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    static final class Store
            implements ProcessedEvents, AssessmentFacts, DecisionFacts, AiDayFacts, LatencyFacts, ApplicationDirectory {
        final Set<String> claimed = new HashSet<>();
        final Map<String, ApplicationFact> applications = new LinkedHashMap<>();
        final Map<String, AssessmentFact> assessments = new LinkedHashMap<>();
        final Map<String, DecisionFact> decisions = new LinkedHashMap<>();
        final Map<String, OfferFact> offers = new LinkedHashMap<>();
        final Map<String, AiDayFact> aiDays = new LinkedHashMap<>();
        final Map<String, LatencyFact> latencies = new LinkedHashMap<>();
        final Map<String, String> titles = new HashMap<>();
        final Map<String, ApplicationInfo> directory = new HashMap<>();
        int directoryCalls;
        boolean directoryDown;

        @Override
        public boolean claim(String consumer, String eventId, Instant at) {
            return claimed.add(consumer + ":" + eventId);
        }

        @Override
        public void release(String consumer, String eventId) {
            claimed.remove(consumer + ":" + eventId);
        }

        final ApplicationFacts apps = new ApplicationFacts() {
            @Override
            public Optional<ApplicationFact> find(String tenantId, String applicationId) {
                return Optional.ofNullable(applications.get(tenantId + "|" + applicationId));
            }

            @Override
            public void save(ApplicationFact fact) {
                applications.put(fact.tenantId() + "|" + fact.applicationId(), fact);
            }

            @Override
            public List<ApplicationFact> touchedSince(String tenantId, String jobId, Instant since) {
                return applications.values().stream()
                        .filter(f -> f.tenantId().equals(tenantId))
                        .filter(f -> jobId == null || jobId.equals(f.jobId()))
                        .filter(f -> f.lastEventAt() != null && !f.lastEventAt().isBefore(since))
                        .toList();
            }
        };

        final OfferFacts offerPort = new OfferFacts() {
            @Override
            public Optional<OfferFact> find(String tenantId, String offerId) {
                return Optional.ofNullable(offers.get(tenantId + "|" + offerId));
            }

            @Override
            public void save(OfferFact fact) {
                offers.put(fact.tenantId() + "|" + fact.offerId(), fact);
            }

            @Override
            public List<OfferFact> touchedSince(String tenantId, Instant since) {
                return offers.values().stream()
                        .filter(o -> o.tenantId().equals(tenantId)
                                && !o.lastEventAt().isBefore(since))
                        .toList();
            }
        };

        final JobTitles titlePort = new JobTitles() {
            @Override
            public Optional<String> find(String tenantId, String jobId) {
                return Optional.ofNullable(titles.get(tenantId + "|" + jobId));
            }

            @Override
            public void put(String tenantId, String jobId, String title) {
                titles.put(tenantId + "|" + jobId, title);
            }
        };

        Optional<ApplicationFact> app(String applicationId) {
            return apps.find("t1", applicationId);
        }

        ProjectionService projections(Clock clock) {
            return new ProjectionService(this, apps, this, this, offerPort, this, this, titlePort, this, clock);
        }

        AnalyticsQueryService queries(Clock clock) {
            return new AnalyticsQueryService(apps, this, this, offerPort, this, titlePort, clock);
        }

        @Override
        public void save(AssessmentFact fact) {
            assessments.put(fact.tenantId() + "|" + fact.assessmentId(), fact);
        }

        @Override
        public List<AssessmentFact> forApplications(String tenantId, Collection<String> applicationIds) {
            return assessments.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId) && applicationIds.contains(a.applicationId()))
                    .toList();
        }

        @Override
        public List<AssessmentFact> completedBetween(String tenantId, Instant from, Instant to) {
            return assessments.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId)
                            && a.completedAt().isAfter(from)
                            && !a.completedAt().isAfter(to))
                    .toList();
        }

        @Override
        public boolean add(DecisionFact decision) {
            return decisions.putIfAbsent(decision.tenantId() + "|" + decision.id(), decision) == null;
        }

        @Override
        public void markLatestUndone(String tenantId, String applicationId) {
            decisions.entrySet().stream()
                    .filter(e -> e.getValue().tenantId().equals(tenantId)
                            && e.getValue().applicationId().equals(applicationId)
                            && e.getValue().kind() != DecisionKind.OVERRIDE
                            && !e.getValue().undone())
                    .max(Comparator.comparing(e -> e.getValue().at()))
                    .ifPresent(e -> e.setValue(e.getValue().undo()));
        }

        @Override
        public List<DecisionFact> between(String tenantId, Instant from, Instant to) {
            return decisions.values().stream()
                    .filter(d -> d.tenantId().equals(tenantId) && d.at().isAfter(from) && !d.at().isAfter(to))
                    .toList();
        }

        @Override
        public void add(AiSample s) {
            var key = s.tenantId() + "|" + s.day() + "|" + s.task() + "|" + s.model();
            var old = aiDays.get(key);
            var buckets = old == null
                    ? new long[Facts.LATENCY_BOUNDS.length + 1]
                    : old.latencyBuckets().clone();
            buckets[Facts.bucket(s.latencyMs())]++;
            aiDays.put(
                    key,
                    new AiDayFact(
                            s.tenantId(),
                            s.day(),
                            s.task(),
                            s.model(),
                            (old == null ? 0 : old.requests()) + 1,
                            (old == null ? 0 : old.errors()) + (s.error() ? 1 : 0),
                            (old == null ? 0 : old.promptTokens()) + s.promptTokens(),
                            (old == null ? 0 : old.completionTokens()) + s.completionTokens(),
                            (old == null ? 0 : old.costInr()) + s.costInr(),
                            (old == null ? 0 : old.latencyMsSum()) + s.latencyMs(),
                            buckets));
        }

        @Override
        public List<AiDayFact> between(String tenantId, LocalDate from, LocalDate to) {
            return aiDays.values().stream()
                    .filter(d -> d.tenantId().equals(tenantId)
                            && !d.day().isBefore(from)
                            && !d.day().isAfter(to))
                    .toList();
        }

        @Override
        public void mark(String tenantId, LatencyKind kind, String key, Edge edge, Instant at) {
            var id = tenantId + "|" + kind + "|" + key;
            var old = latencies.getOrDefault(id, new LatencyFact(tenantId, kind, key, null, null));
            latencies.put(
                    id,
                    edge == Edge.START
                            ? new LatencyFact(tenantId, kind, key, min(old.startAt(), at), old.endAt())
                            : new LatencyFact(tenantId, kind, key, old.startAt(), min(old.endAt(), at)));
        }

        private static Instant min(Instant a, Instant b) {
            return a == null || b.isBefore(a) ? b : a;
        }

        @Override
        public List<LatencyFact> startedSince(String tenantId, LatencyKind kind, Instant since) {
            return latencies.values().stream()
                    .filter(f -> f.kind() == kind
                            && (tenantId == null || tenantId.equals(f.tenantId()))
                            && f.startAt() != null
                            && !f.startAt().isBefore(since))
                    .toList();
        }

        @Override
        public Optional<ApplicationInfo> lookup(String tenantId, String applicationId) {
            directoryCalls++;
            if (directoryDown) throw new IllegalStateException("application-service is down");
            return Optional.ofNullable(directory.get(tenantId + "|" + applicationId));
        }
    }

    static final class Health implements HealthStore {
        final List<ServiceSample> samples = new ArrayList<>();

        @Override
        public void add(ServiceSample sample) {
            samples.add(sample);
        }

        @Override
        public Optional<ServiceSample> latest(String service) {
            return samples.stream()
                    .filter(s -> s.service().equals(service))
                    .max(Comparator.comparing(ServiceSample::at));
        }

        @Override
        public List<ServiceSample> since(String service, Instant since) {
            return samples.stream()
                    .filter(s -> s.service().equals(service) && !s.at().isBefore(since))
                    .sorted(Comparator.comparing(ServiceSample::at))
                    .toList();
        }
    }

    static final class Replays implements ReplayLog {
        final Map<String, Replay> rows = new LinkedHashMap<>();

        @Override
        public Optional<Replay> find(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<Replay> findAll(Collection<String> ids) {
            return ids.stream()
                    .map(rows::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        @Override
        public boolean insert(Replay replay) {
            return rows.putIfAbsent(replay.id(), replay) == null;
        }

        @Override
        public void update(Replay replay) {
            rows.put(replay.id(), replay);
        }

        @Override
        public void delete(String id) {
            rows.remove(id);
        }

        @Override
        public List<Instant> since(String tenantId, Instant since) {
            return rows.values().stream()
                    .filter(r ->
                            r.tenantId().equals(tenantId) && !r.replayedAt().isBefore(since))
                    .map(Replay::replayedAt)
                    .toList();
        }
    }

    static final class DeadLetters implements DeadLetterSource {
        final List<DeadLetterRecord> records = new ArrayList<>();

        @Override
        public List<DeadLetterRecord> recent(int perPartition) {
            return List.copyOf(records);
        }

        @Override
        public Optional<DeadLetterRecord> find(String topic, int partition, long offset) {
            return records.stream()
                    .filter(r -> r.topic().equals(topic) && r.partition() == partition && r.offset() == offset)
                    .findFirst();
        }
    }
}
