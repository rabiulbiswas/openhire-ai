package com.openhire.analytics.application.port;

import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.DeadLetterRecord;
import com.openhire.analytics.domain.Facts.AiDayFact;
import com.openhire.analytics.domain.Facts.AiSample;
import com.openhire.analytics.domain.Facts.AssessmentFact;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyFact;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.Facts.OfferFact;
import com.openhire.analytics.domain.ServiceSample;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Outbound ports of the analytics application layer. */
public final class AnalyticsPorts {
    private AnalyticsPorts() {}

    /** Idempotency fence: an event id is claimed once per consumer. */
    public interface ProcessedEvents {
        boolean claim(String consumer, String eventId, Instant at);

        void release(String consumer, String eventId);
    }

    public interface ApplicationFacts {
        Optional<ApplicationFact> find(String tenantId, String applicationId);

        void save(ApplicationFact fact);

        /** Applications of the tenant (optionally one job) with any event at or after the instant. */
        List<ApplicationFact> touchedSince(String tenantId, String jobId, Instant since);
    }

    public interface AssessmentFacts {
        void save(AssessmentFact fact);

        List<AssessmentFact> forApplications(String tenantId, Collection<String> applicationIds);

        /** Assessments completed in the half-open window (from, to]. */
        List<AssessmentFact> completedBetween(String tenantId, Instant from, Instant to);
    }

    public interface DecisionFacts {
        /** Returns false when a decision with this id already exists. */
        boolean add(DecisionFact decision);

        /** Marks the application's latest shortlist or rejection as undone. */
        void markLatestUndone(String tenantId, String applicationId);

        /** Decisions made in the half-open window (from, to]. */
        List<DecisionFact> between(String tenantId, Instant from, Instant to);
    }

    public interface OfferFacts {
        Optional<OfferFact> find(String tenantId, String offerId);

        void save(OfferFact fact);

        List<OfferFact> touchedSince(String tenantId, Instant since);
    }

    public interface AiDayFacts {
        void add(AiSample sample);

        /** Days from the first to the last, both included. */
        List<AiDayFact> between(String tenantId, LocalDate from, LocalDate to);
    }

    public interface LatencyFacts {
        void mark(String tenantId, LatencyKind kind, String key, Edge edge, Instant at);

        /** Units of work that started at or after the instant, finished or not; a null tenant means all tenants. */
        List<LatencyFact> startedSince(String tenantId, LatencyKind kind, Instant since);
    }

    public interface JobTitles {
        Optional<String> find(String tenantId, String jobId);

        void put(String tenantId, String jobId, String title);
    }

    /** The application projection of application-service, used only to fill what events do not carry. */
    public interface ApplicationDirectory {
        Optional<ApplicationInfo> lookup(String tenantId, String applicationId);

        record ApplicationInfo(String jobId, String jobTitle, String source, Instant appliedAt) {}
    }

    public interface HealthStore {
        void add(ServiceSample sample);

        Optional<ServiceSample> latest(String service);

        List<ServiceSample> since(String service, Instant since);
    }

    /** Reads a service's actuator endpoints. Never throws: an unreachable service is a result, not an error. */
    public interface ActuatorClient {
        Health health(String baseUrl);

        Optional<HttpMetrics> httpMetrics(String baseUrl, boolean serverErrorsOnly);

        record Health(boolean reachable, String status, long responseMs) {}

        record HttpMetrics(long count, double totalSeconds, double maxSeconds) {}
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }

    public interface ConsumerOffsets {
        List<PartitionOffset> offsets();

        record PartitionOffset(String group, String topic, int partition, long committed, long end) {}
    }

    public interface DeadLetterSource {
        /** The newest messages of every dead-letter topic, at most {@code perPartition} from each partition. */
        List<DeadLetterRecord> recent(int perPartition);

        Optional<DeadLetterRecord> find(String topic, int partition, long offset);
    }

    public interface Republisher {
        void publish(String topic, String key, String value, Map<String, String> headers);
    }

    public interface ReplayLog {
        Optional<Replay> find(String id);

        List<Replay> findAll(Collection<String> ids);

        /** Returns false when the message already has a replay record. */
        boolean insert(Replay replay);

        void update(Replay replay);

        void delete(String id);

        /** Replay times for the tenant since the instant, for the rate limit. */
        List<Instant> since(String tenantId, Instant since);
    }

    /** What the reviewer typed is kept here, not in the audit record. */
    public record Replay(
            String id,
            String tenantId,
            String originalTopic,
            String reason,
            String replayedBy,
            Instant replayedAt,
            int count,
            boolean forced) {}

    public interface AuditTrail {
        void deadLetterReplayed(String tenantId, String actorId, String actorName, String id, String originalTopic);
    }
}
