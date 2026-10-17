package com.openhire.aiworkers.integrity.application.port;

import com.openhire.aiworkers.integrity.domain.Role;
import com.openhire.aiworkers.integrity.domain.Signal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IntegrityPorts {
    private IntegrityPorts() {}

    public record ApplicationInfo(
            String candidateId, String candidateName, String candidateEmail, String candidatePhone, String resumeId) {}

    /** The application's own contact details, read from application-service. */
    public interface ApplicationDirectory {
        ApplicationInfo application(String tenantId, String applicationId);
    }

    /** Structured roles from the model gateway; throws when the gateway is unavailable. */
    public interface Structuring {
        List<Role> roles(String tenantId, String text);
    }

    public record IdentityEntry(
            String tenantId,
            String applicationId,
            String candidateId,
            String kind,
            String valueHash,
            Set<String> nameTokens) {}

    public interface IdentityIndex {
        void register(IdentityEntry entry);

        /** Entries with the same hashed value that belong to a different candidate. */
        List<IdentityEntry> others(String tenantId, String kind, String valueHash, String candidateId);
    }

    public interface SignalStore {
        /** Returns false when the same signal already exists (redelivery); the event is stored with the signal. */
        boolean insertIfAbsent(Signal signal, Map<String, Object> event);

        List<Signal> byApplication(String tenantId, String applicationId);

        Optional<Signal> find(String tenantId, String id);

        /** Marks a raised signal dismissed, records the reason for threshold tuning and stores the event. */
        Optional<Signal> dismiss(String tenantId, String id, String actor, String reason, Map<String, Object> event);
    }
}
