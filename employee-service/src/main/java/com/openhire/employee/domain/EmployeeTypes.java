package com.openhire.employee.domain;

import java.time.Instant;
import java.util.Map;

public final class EmployeeTypes {
    private EmployeeTypes() {}

    public enum EmployeeStatus {
        PRE_BOARDING,
        ACTIVE,
        EXITED
    }

    /** What the console shows for the HRIS hand-off. */
    public enum HrSync {
        PENDING,
        RETRYING,
        SYNCED
    }

    public enum SagaStep {
        SNAPSHOT,
        CREATE_EMPLOYEE,
        UPDATE_APPLICATIONS,
        ONBOARDING,
        HRIS_SYNC,
        COMPLETE
    }

    /** FAILED means a mandatory step ran out of retries and the saga is parked for an operator. */
    public enum SagaStatus {
        RUNNING,
        COMPLETED,
        FAILED
    }

    public enum HrisStatus {
        NOT_REQUESTED,
        REQUESTED,
        SYNCED,
        RETRYING,
        COMPENSATE_PENDING
    }

    public enum RewardStatus {
        PENDING_PROBATION,
        ELIGIBLE
    }

    public record OnboardingTask(String id, String label, String group, boolean done, Instant doneAt) {}

    public record TemplateTask(String id, String label, String group) {}

    public record Contact(String name, String email) {}

    /** Candidate contact details and attribution copied from the application at hire time. */
    public record Snapshot(String name, String email, String phone, String source, String actorId) {}

    /** The facts of an accepted offer that the saga needs; everything else is looked up. */
    public record OfferAccepted(
            String tenantId,
            String offerId,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String ownerId,
            String joiningDate,
            String designation,
            String location,
            String reportsTo) {}

    /** Pending integration event, stored inside the aggregate so it commits atomically with the change. */
    public record PendingEvent(
            String eventId,
            String producer,
            String eventType,
            int eventVersion,
            String occurredAt,
            String tenantId,
            String aggregateType,
            String aggregateId,
            String correlationId,
            Map<String, Object> payload) {}
}
