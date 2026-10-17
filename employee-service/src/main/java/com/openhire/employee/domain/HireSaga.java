package com.openhire.employee.domain;

import com.openhire.employee.domain.EmployeeTypes.HrisStatus;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.EmployeeTypes.SagaStatus;
import com.openhire.employee.domain.EmployeeTypes.SagaStep;
import com.openhire.employee.domain.EmployeeTypes.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted state machine for one accepted offer. A worker executes the current step and stores the result, so a
 * restart resumes at the stored step. Steps are idempotent; only the transitions below move the saga forward.
 *
 * <p>The HRIS hand-off is optional: the saga completes once the request is emitted, and later outcomes only update
 * {@link #hrisStatus()}.
 */
@Document("hire_sagas")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_application", def = "{'tenantId': 1, 'applicationId': 1}"),
    @CompoundIndex(name = "saga_due", def = "{'nextAttemptAt': 1, 'leaseUntil': 1}"),
    @CompoundIndex(name = "tenant_status", def = "{'tenantId': 1, 'status': 1, 'updatedAt': -1}")
})
public class HireSaga {
    private static final int MAX_ERROR = 300;

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String offerId;
    private String applicationId;
    private String candidateId;
    private String candidateName;
    private String jobId;
    private String jobTitle;
    private String ownerId;
    private String joiningDate;
    private String designation;
    private String location;
    private String reportsTo;

    private SagaStep step;
    private SagaStatus status;
    private boolean stuck;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant stepEnteredAt;
    private Instant leaseUntil;
    private String leaseOwner;
    private String lastError;

    private Snapshot snapshot;
    private String employeeNo;
    private String employeeId;
    private boolean applicationHired;
    private HrisStatus hrisStatus = HrisStatus.NOT_REQUESTED;
    private int hrisRequests;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    protected HireSaga() {}

    public static String idFor(String offerId) {
        return "HIRE-" + offerId;
    }

    public static HireSaga start(OfferAccepted offer, Instant now) {
        try {
            LocalDate.parse(offer.joiningDate());
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException("The accepted offer has no valid joining date");
        }
        var saga = new HireSaga();
        saga.id = idFor(offer.offerId());
        saga.tenantId = offer.tenantId();
        saga.offerId = offer.offerId();
        saga.applicationId = offer.applicationId();
        saga.candidateId = offer.candidateId();
        saga.candidateName = offer.candidateName();
        saga.jobId = offer.jobId();
        saga.jobTitle = offer.jobTitle();
        saga.ownerId = offer.ownerId();
        saga.joiningDate = offer.joiningDate();
        saga.designation = offer.designation();
        saga.location = offer.location();
        saga.reportsTo = offer.reportsTo();
        saga.status = SagaStatus.RUNNING;
        saga.createdAt = now;
        saga.enter(SagaStep.SNAPSHOT, now);
        return saga;
    }

    public OfferAccepted offer() {
        return new OfferAccepted(
                tenantId,
                offerId,
                applicationId,
                candidateId,
                candidateName,
                jobId,
                jobTitle,
                ownerId,
                joiningDate,
                designation,
                location,
                reportsTo);
    }

    /** A worker may take the saga when its next attempt is due and no other worker holds a live lease. */
    public boolean due(Instant now) {
        return nextAttemptAt != null && !nextAttemptAt.isAfter(now) && (leaseUntil == null || !leaseUntil.isAfter(now));
    }

    public void claim(Instant now, Duration lease, String owner) {
        leaseUntil = now.plus(lease);
        leaseOwner = owner;
    }

    public void release(Instant now) {
        leaseUntil = null;
        leaseOwner = null;
        updatedAt = now;
    }

    /** True while the current step may run immediately (used to chain steps within one claim). */
    public boolean runnable(Instant now) {
        return nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    public void snapshotTaken(Snapshot value, Instant now) {
        require(SagaStep.SNAPSHOT);
        snapshot = value;
        enter(SagaStep.CREATE_EMPLOYEE, now);
    }

    /** Stored before the employee is written so a replay reuses the number instead of burning another. */
    public void employeeNumberAllocated(String number) {
        if (employeeNo == null) employeeNo = number;
    }

    public void employeeCreated(String id, Instant now) {
        require(SagaStep.CREATE_EMPLOYEE);
        employeeId = id;
        snapshot = null;
        enter(applicationHired ? SagaStep.ONBOARDING : SagaStep.UPDATE_APPLICATIONS, now);
    }

    /** Idempotent; the event may arrive before the saga reaches the waiting step. */
    public void applicationHired(Instant now) {
        applicationHired = true;
        if (step == SagaStep.UPDATE_APPLICATIONS) enter(SagaStep.ONBOARDING, now);
        else updatedAt = now;
    }

    public void waitForApplication(Instant now, Duration poll, Duration stuckAfter) {
        require(SagaStep.UPDATE_APPLICATIONS);
        nextAttemptAt = now.plus(poll);
        if (!stepEnteredAt.plus(stuckAfter).isAfter(now)) stuck = true;
        updatedAt = now;
    }

    public void checklistReady(Instant now) {
        require(SagaStep.ONBOARDING);
        enter(SagaStep.HRIS_SYNC, now);
    }

    public void hrisRequested(Instant now) {
        hrisRequests++;
        hrisStatus = HrisStatus.REQUESTED;
        if (step == SagaStep.HRIS_SYNC) enter(SagaStep.COMPLETE, now);
        else {
            nextAttemptAt = null;
            updatedAt = now;
        }
    }

    public void complete(Instant now) {
        require(SagaStep.COMPLETE);
        status = SagaStatus.COMPLETED;
        stuck = false;
        lastError = null;
        completedAt = now;
        if (hrisStatus != HrisStatus.RETRYING) nextAttemptAt = null;
        updatedAt = now;
    }

    public void hrisSynced(Instant now) {
        if (hrisStatus == HrisStatus.SYNCED) return;
        hrisStatus = HrisStatus.SYNCED;
        if (status == SagaStatus.COMPLETED) nextAttemptAt = null;
        updatedAt = now;
    }

    /** Returns true when a retry is scheduled, false once HR Ops has to take over. */
    public boolean hrisFailed(Instant now, List<Duration> delays) {
        if (hrisStatus != HrisStatus.REQUESTED) return hrisStatus == HrisStatus.RETRYING;
        updatedAt = now;
        if (hrisRequests <= delays.size()) {
            hrisStatus = HrisStatus.RETRYING;
            nextAttemptAt = now.plus(delays.get(hrisRequests - 1));
            return true;
        }
        hrisStatus = HrisStatus.COMPENSATE_PENDING;
        nextAttemptAt = null;
        lastError = "HRIS sync failed repeatedly; HR Ops follow-up required";
        return false;
    }

    /** Records a failed attempt of the current step and schedules the back-off, or parks the saga. */
    public void fail(String error, Instant now, List<Duration> delays) {
        attempts++;
        lastError = error == null ? "Unknown error" : error.substring(0, Math.min(error.length(), MAX_ERROR));
        updatedAt = now;
        if (attempts > delays.size()) {
            status = SagaStatus.FAILED;
            stuck = true;
            nextAttemptAt = null;
        } else nextAttemptAt = now.plus(delays.get(attempts - 1));
    }

    /** Operator retry: resumes a parked or stuck saga at its stored step, or re-sends a failed HRIS request. */
    public void retry(Instant now) {
        if (status == SagaStatus.COMPLETED) {
            if (hrisStatus != HrisStatus.RETRYING && hrisStatus != HrisStatus.COMPENSATE_PENDING)
                throw new IllegalStateException("The hire saga is already complete");
            hrisStatus = HrisStatus.RETRYING;
            hrisRequests = 0;
            nextAttemptAt = now;
            lastError = null;
        } else {
            status = SagaStatus.RUNNING;
            stuck = false;
            attempts = 0;
            lastError = null;
            stepEnteredAt = now;
            nextAttemptAt = now;
        }
        leaseUntil = null;
        leaseOwner = null;
        updatedAt = now;
    }

    /** Pushes the next attempt back without counting a failure, for work outside the mandatory steps. */
    public void postpone(Instant now, Duration delay) {
        nextAttemptAt = now.plus(delay);
        updatedAt = now;
    }

    private void enter(SagaStep next, Instant now) {
        step = next;
        attempts = 0;
        stuck = false;
        lastError = null;
        stepEnteredAt = now;
        nextAttemptAt = now;
        updatedAt = now;
    }

    private void require(SagaStep expected) {
        if (step != expected) throw new IllegalStateException("Saga is at " + step + ", not " + expected);
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String offerId() {
        return offerId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String candidateName() {
        return candidateName;
    }

    public SagaStep step() {
        return step;
    }

    public SagaStatus status() {
        return status;
    }

    public boolean stuck() {
        return stuck;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public String employeeNo() {
        return employeeNo;
    }

    public String employeeId() {
        return employeeId;
    }

    public boolean applicationWasHired() {
        return applicationHired;
    }

    public HrisStatus hrisStatus() {
        return hrisStatus;
    }

    public int hrisRequests() {
        return hrisRequests;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant leaseUntil() {
        return leaseUntil;
    }
}
