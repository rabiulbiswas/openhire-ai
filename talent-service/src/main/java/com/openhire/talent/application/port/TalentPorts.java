package com.openhire.talent.application.port;

import com.openhire.talent.domain.Invitation;
import com.openhire.talent.domain.RediscoveryRun;
import com.openhire.talent.domain.TalentProfile;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.domain.TalentTypes.Filters;
import com.openhire.talent.domain.TalentTypes.StructuredResume;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TalentPorts {
    private TalentPorts() {}

    /** Another writer saved the same profile first; the caller re-reads and applies its change again. */
    public static final class ConcurrentUpdate extends RuntimeException {
        public ConcurrentUpdate(Throwable cause) {
            super("Profile changed concurrently", cause);
        }
    }

    /** The model server is down, over budget, too slow or returned unusable output; callers fall back. */
    public static final class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ReceiptRejected extends RuntimeException {
        public ReceiptRejected() {
            super("Invalid application receipt");
        }
    }

    public interface ProfileRepository {
        Optional<TalentProfile> find(String tenantId, String candidateId);

        Optional<TalentProfile> findByApplication(String tenantId, String applicationId);

        /** Throws ConcurrentUpdate when the stored revision moved on. */
        TalentProfile save(TalentProfile profile);

        void delete(String tenantId, String candidateId);

        /** Consent granted, active, content built, retention valid, and not an employee unless asked. */
        List<TalentProfile> searchable(String tenantId, Instant now, boolean includeEmployees);

        /** Active profiles whose retention date has passed, across tenants. */
        List<TalentProfile> due(Instant now, int limit);

        /** Consented profiles whose content is built but whose vector is missing (model was down). */
        List<TalentProfile> awaitingEmbedding(int limit);
    }

    public interface VectorStore {
        void save(String tenantId, String candidateId, float[] vector);

        void delete(String tenantId, String candidateId);

        /** Every vector of the tenant, keyed by candidate id. */
        Map<String, float[]> all(String tenantId);
    }

    public interface RunRepository {
        RediscoveryRun save(RediscoveryRun run);

        Optional<RediscoveryRun> latest(String tenantId, String jobId);

        Optional<RediscoveryRun> latestForTenant(String tenantId);

        boolean existsByTrigger(String tenantId, String triggerEventId);

        /** Erases a candidate from stored runs, so revocation and expiry leave no explanation text behind. */
        void removeCandidate(String tenantId, String candidateId);
    }

    public interface InvitationRepository {
        Invitation save(Invitation invitation);

        Optional<Invitation> latestFor(String tenantId, String candidateId);

        List<Invitation> since(String tenantId, Instant since);
    }

    public record ApplicationInfo(
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String stage,
            Instant appliedAt,
            String resumeId) {}

    public record ReceiptInfo(String candidateId, String candidateName) {}

    public interface ApplicationDirectory {
        ApplicationInfo application(String tenantId, String applicationId);

        /** Throws ReceiptRejected when the receipt does not belong to the application. */
        ReceiptInfo verifyReceipt(String tenantId, String applicationId, String receipt);
    }

    public interface ResumeDirectory {
        /** The extracted text, or empty while the resume is not DONE. */
        Optional<String> text(String tenantId, String resumeId);
    }

    public record JobInfo(
            String id,
            String title,
            String status,
            String description,
            List<String> mustHaveSkills,
            List<String> criteria) {}

    public interface JobDirectory {
        JobInfo job(String tenantId, String jobId);
    }

    public record TemplateSend(boolean sent, String reason) {}

    public interface Messenger {
        TemplateSend sendTemplate(
                String tenantId, String applicationId, String templateKey, Map<String, String> variables, String actor);
    }

    public record NlFilter(Filters filters, String semanticQuery, List<String> chips) {}

    public record Explanation(String whyFitNow, String historicalEvidence) {}

    public record ExplainRequest(
            String jobTitle, String jobSummary, List<String> criteria, List<Chunk> historical, List<Chunk> current) {}

    /** The ai-gateway tasks this service uses. Every method may throw AiUnavailableException. */
    public interface AiGateway {
        float[] embed(String tenantId, String text);

        StructuredResume structureResume(String tenantId, String text);

        NlFilter nlFilter(String tenantId, String query);

        Explanation explainFit(String tenantId, ExplainRequest request);
    }

    public interface EventOutbox {
        void publish(
                String tenantId, String type, String aggregateType, String aggregateId, Map<String, Object> payload);

        /** Removes outbox documents whose events were all delivered. */
        void purgeDelivered(Instant before);
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }
}
