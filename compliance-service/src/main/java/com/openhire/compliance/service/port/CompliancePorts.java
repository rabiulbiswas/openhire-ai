package com.openhire.compliance.service.port;

import com.openhire.compliance.domain.AuditEntry;
import com.openhire.platform.audit.AuditRecord;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything the application layer needs from the outside world. Documents are plain JSON-shaped maps. */
public final class CompliancePorts {
    private CompliancePorts() {}

    public enum InsertResult {
        INSERTED,
        SEQ_CONFLICT,
        DUPLICATE_EVENT
    }

    public record AuditFilter(
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String candidateId,
            String applicationId,
            String from,
            String to) {
        public static AuditFilter none() {
            return new AuditFilter(null, null, null, null, null, null, null, null);
        }

        public AuditFilter forApplication(String applicationId) {
            return new AuditFilter(null, null, null, null, null, applicationId, null, null);
        }

        public AuditFilter forCandidate(String candidateId) {
            return new AuditFilter(null, null, null, null, candidateId, null, null, null);
        }
    }

    public interface AuditStore {
        Optional<AuditEntry> last(String tenantId);

        /** Fails with SEQ_CONFLICT when the tenant already has that seq, DUPLICATE_EVENT when the event was stored. */
        InsertResult insert(AuditEntry entry);

        /** Newest first; {@code beforeSeq} (exclusive) continues a previous page. */
        List<AuditEntry> page(String tenantId, AuditFilter filter, Long beforeSeq, int limit);

        /** Oldest first, for verification and export. */
        List<AuditEntry> after(String tenantId, long afterSeq, String from, String to, int limit);

        long countSince(String tenantId, String from);
    }

    /** The per-application activity projection (subject_activity). Saves are optimistic on the {@code version} field. */
    public interface ActivityStore {
        Optional<Map<String, Object>> find(String tenantId, String applicationId);

        /** True when saved; false when someone else saved first (reload and retry). Inserts when version is 0. */
        boolean save(Map<String, Object> doc);

        List<Map<String, Object>> byCandidate(String tenantId, String candidateId);

        List<Map<String, Object>> byJob(String tenantId, String jobId);

        List<Map<String, Object>> all(String tenantId);

        /** Applications whose last activity is older than the cutoff, oldest first. */
        List<Map<String, Object>> idleSince(String tenantId, String cutoff, int limit);

        List<String> tenants();

        void deleteByCandidate(String tenantId, String candidateId);
    }

    public interface DecisionStore {
        void save(Map<String, Object> decision);

        Optional<Map<String, Object>> find(String tenantId, String id);

        List<Map<String, Object>> list(
                String tenantId, String candidateId, String applicationId, String task, String from, int limit);

        void deleteByCandidate(String tenantId, String candidateId);
    }

    public interface ConsentStore {
        List<Map<String, Object>> texts(String tenantId);

        Optional<Map<String, Object>> active(String tenantId, String purpose);

        /** Retires the purpose's active text and stores the new one. */
        void publish(Map<String, Object> text);
    }

    public interface PrivacyStore {
        void insert(Map<String, Object> request);

        Optional<Map<String, Object>> find(String tenantId, String id);

        List<Map<String, Object>> list(String tenantId, String status, String candidateId, int limit);

        /** Requests in the status across all tenants, for the sweeps. */
        List<Map<String, Object>> withStatus(String status, int limit);

        /** Replaces the request when its status is one of {@code from}; true when it was replaced. */
        boolean replace(Map<String, Object> request, List<String> from);

        /**
         * Records a participant's answer once: only when that participant is still PENDING on an IN_PROGRESS request.
         * Returns the updated request, empty when ignored (duplicate, unknown request or participant).
         */
        Optional<Map<String, Object>> recordAck(
                String tenantId, String id, String service, Map<String, Object> entry, Map<String, Object> section);

        /** Atomically flags an IN_PROGRESS request as stuck. */
        void markStuck(String tenantId, String id);

        /** After erasure: replaces the candidate id with its hash on every request and deletes their exports. */
        void anonymise(String tenantId, String candidateId, String candidateIdHash);

        void saveExport(String tenantId, String requestId, Map<String, Object> export, String purgeAt);

        Optional<Map<String, Object>> export(String tenantId, String requestId);

        long countOpen(String tenantId);

        long countStuck(String tenantId);
    }

    public interface HoldStore {
        void insert(Map<String, Object> hold);

        Optional<Map<String, Object>> find(String tenantId, String id);

        List<Map<String, Object>> list(String tenantId, String status);

        void replace(Map<String, Object> hold);

        List<Map<String, Object>> active(String tenantId);
    }

    public interface PolicyStore {
        List<Map<String, Object>> overrides(String tenantId);

        void save(Map<String, Object> override);
    }

    /** Executes only non-candidate retention, through each data owner's adapter. */
    public interface RecordRetention {
        List<String> tenants();

        Map<String, Object> run(String tenant, String policy, String cutoff, boolean dryRun, int limit);
    }

    public interface SelfIdentificationStore {
        void save(String tenantId, String applicationId, String candidateId, String ciphertext, String at);

        Optional<String> ciphertext(String tenantId, String applicationId);

        Map<String, String> ciphertexts(String tenantId, List<String> applicationIds);

        void deleteByCandidate(String tenantId, String candidateId, List<String> applicationIds);
    }

    public interface FairnessStore {
        Optional<Map<String, Object>> latest(String tenantId, String jobId, String dimension);

        void save(Map<String, Object> snapshot);
    }

    public interface Cipher {
        String encrypt(String plaintext, String context);

        String decrypt(String ciphertext, String context);
    }

    public interface Events {
        void publish(
                String tenantId,
                String aggregateType,
                String aggregateId,
                String eventType,
                Map<String, Object> payload);
    }

    public interface AuditSink {
        void record(AuditRecord record);
    }

    /** Remembers erased candidates and applications so late events cannot bring their data back. */
    public interface Erased {
        boolean isErased(String tenantId, String id);

        void mark(String tenantId, String id, String requestId);
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }

    public record AppInfo(
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String stage,
            String source) {}

    public static class DirectoryUnavailable extends RuntimeException {
        public DirectoryUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public interface ApplicationDirectory {
        /** Cached for a short time; empty when the application does not exist. */
        Optional<AppInfo> application(String tenantId, String applicationId);

        /** Never cached. */
        Optional<AppInfo> currentApplication(String tenantId, String applicationId);

        /** Verifies the candidate's receipt with application-service and returns the candidate id. */
        String verifyReceipt(String tenantId, String applicationId, String receipt);
    }
}
