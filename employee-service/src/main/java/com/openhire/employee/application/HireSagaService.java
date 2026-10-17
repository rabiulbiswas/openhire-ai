package com.openhire.employee.application;

import com.openhire.employee.application.port.EmployeePorts.ApplicationDirectory;
import com.openhire.employee.application.port.EmployeePorts.EmployeeRepository;
import com.openhire.employee.application.port.EmployeePorts.RewardRepository;
import com.openhire.employee.application.port.EmployeePorts.SagaRepository;
import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.HrSync;
import com.openhire.employee.domain.EmployeeTypes.HrisStatus;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.EmployeeTypes.SagaStatus;
import com.openhire.employee.domain.EmployeeTypes.Snapshot;
import com.openhire.employee.domain.HireSaga;
import com.openhire.employee.domain.OnboardingTemplate;
import com.openhire.employee.domain.ReferralReward;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Runs the hire saga. {@link #start} only records the saga; {@link #runDue} (called by the worker) claims due sagas
 * with a lease and executes their stored step, so a crash at any point resumes at the persisted step.
 */
@Service
public class HireSagaService {
    private static final Logger LOG = LoggerFactory.getLogger(HireSagaService.class);
    private static final int MAX_STEPS_PER_CLAIM = 20;
    private static final String REFERRAL = "REFERRAL";

    private final SagaRepository sagas;
    private final EmployeeRepository employees;
    private final RewardRepository rewards;
    private final EmployeeNumberAllocator numbers;
    private final ApplicationDirectory applications;
    private final EmployeeSettings settings;
    private final Clock clock;
    private final AuditRecorder audit;
    private final String owner = UUID.randomUUID().toString();

    public HireSagaService(
            SagaRepository sagas,
            EmployeeRepository employees,
            RewardRepository rewards,
            EmployeeNumberAllocator numbers,
            ApplicationDirectory applications,
            EmployeeSettings settings,
            Clock clock,
            AuditRecorder audit) {
        this.sagas = sagas;
        this.employees = employees;
        this.rewards = rewards;
        this.numbers = numbers;
        this.applications = applications;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    /** Idempotent on the offer: a duplicate {@code offer.accepted.v1} returns the saga that already exists. */
    public HireSaga start(OfferAccepted offer) {
        return sagas.insertIfAbsent(HireSaga.start(offer, clock.instant()));
    }

    /** Executes up to {@code max} due sagas; returns how many were claimed. */
    public int runDue(int max) {
        int claimed = 0;
        while (claimed < max) {
            var next = sagas.claimNext(clock.instant(), settings.lease(), owner);
            if (next.isEmpty()) break;
            claimed++;
            try {
                advance(next.get());
            } catch (RuntimeException failure) {
                // The lease expires and the stored step is retried; nothing else to undo.
                LOG.warn(
                        "Saga {} could not be saved ({})",
                        next.get().id(),
                        failure.getClass().getSimpleName());
            }
        }
        return claimed;
    }

    /** Runs the current step, persisting after each one, until the saga must wait, parks or completes. */
    void advance(HireSaga saga) {
        for (int i = 0; i < MAX_STEPS_PER_CLAIM; i++) {
            var now = clock.instant();
            if (!saga.runnable(now)) break;
            try {
                execute(saga, now);
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Saga {} step {} failed ({})",
                        saga.id(),
                        saga.step(),
                        failure.getClass().getSimpleName());
                var message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                if (saga.status() == SagaStatus.COMPLETED) saga.postpone(now, settings.applicationPoll());
                else saga.fail(message, now, settings.retryDelays());
            }
            sagas.save(saga);
        }
        saga.release(clock.instant());
        sagas.save(saga);
    }

    private void execute(HireSaga saga, Instant now) {
        if (saga.status() == SagaStatus.COMPLETED) {
            requestHris(saga, now);
            return;
        }
        switch (saga.step()) {
            case SNAPSHOT -> snapshot(saga, now);
            case CREATE_EMPLOYEE -> createEmployee(saga, now);
            case UPDATE_APPLICATIONS -> awaitApplication(saga, now);
            case ONBOARDING -> createChecklist(saga, now);
            case HRIS_SYNC -> requestHris(saga, now);
            case COMPLETE -> saga.complete(now);
        }
    }

    private void snapshot(HireSaga saga, Instant now) {
        var info = applications.application(saga.tenantId(), saga.applicationId());
        saga.snapshotTaken(
                new Snapshot(info.candidateName(), info.email(), info.phone(), info.source(), info.actorId()), now);
    }

    private void createEmployee(HireSaga saga, Instant now) {
        if (saga.employeeNo() == null) {
            saga.employeeNumberAllocated(numbers.allocate(saga.tenantId()));
            sagas.save(saga);
        }
        var employee = employees.findByOffer(saga.tenantId(), saga.offerId()).orElse(null);
        if (employee == null) {
            employee = Employee.create(saga.offer(), saga.snapshot(), saga.employeeNo(), now);
            try {
                employee = employees.save(employee);
            } catch (DuplicateKeyException raced) {
                employee =
                        employees.findByOffer(saga.tenantId(), saga.offerId()).orElseThrow();
            }
        }
        var snapshot = saga.snapshot();
        if (snapshot != null
                && REFERRAL.equals(snapshot.source())
                && snapshot.actorId() != null
                && !snapshot.actorId().isBlank())
            rewards.insertIfAbsent(ReferralReward.pending(employee, snapshot.actorId(), now));
        saga.employeeCreated(employee.id(), now);
    }

    /** Completes on the stage-changed event; polling the application covers an event that was missed. */
    private void awaitApplication(HireSaga saga, Instant now) {
        try {
            if ("HIRED"
                    .equals(applications
                            .application(saga.tenantId(), saga.applicationId())
                            .stage())) {
                saga.applicationHired(now);
                return;
            }
        } catch (RuntimeException unavailable) {
            LOG.debug("Application {} not readable while waiting", saga.applicationId());
        }
        saga.waitForApplication(now, settings.applicationPoll(), settings.stuckAfter());
    }

    private void createChecklist(HireSaga saga, Instant now) {
        var employee = employee(saga);
        if (employee.createChecklist(OnboardingTemplate.standard(), now)) employees.save(employee);
        saga.checklistReady(now);
    }

    private void requestHris(HireSaga saga, Instant now) {
        var employee = employee(saga);
        if (employee.requestHrisSync(saga.hrisRequests() + 1, now)) employees.save(employee);
        saga.hrisRequested(now);
    }

    private Employee employee(HireSaga saga) {
        return employees
                .findById(saga.tenantId(), saga.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee " + saga.employeeId() + " was not found"));
    }

    /** {@code ApplicationStageChanged} to HIRED; sagas of other applications and replays are ignored. */
    public void applicationHired(String tenantId, String applicationId) {
        Retries.optimistic(() -> {
            var saga = sagas.findByApplication(tenantId, applicationId).orElse(null);
            if (saga == null || saga.applicationWasHired()) return null;
            saga.applicationHired(clock.instant());
            return sagas.save(saga);
        });
    }

    /** Outcome of the HRIS hand-off from the integration hub; replays and unknown employees are ignored. */
    public void hrisOutcome(String tenantId, String employeeId, boolean synced) {
        var employee = employees.findById(tenantId, employeeId).orElse(null);
        if (employee == null) return;
        var sagaId = HireSaga.idFor(employee.offerId());
        Retries.optimistic(() -> {
            var saga = sagas.findById(tenantId, sagaId).orElse(null);
            if (saga == null) return null;
            var now = clock.instant();
            if (synced) saga.hrisSynced(now);
            else saga.hrisFailed(now, settings.hrisRetryDelays());
            return sagas.save(saga);
        });
        var status = sagas.findById(tenantId, sagaId).map(HireSaga::hrisStatus).orElse(HrisStatus.NOT_REQUESTED);
        var sync =
                switch (status) {
                    case SYNCED -> HrSync.SYNCED;
                    case RETRYING -> HrSync.RETRYING;
                    default -> HrSync.PENDING;
                };
        Retries.optimistic(() -> {
            var current = employees.findById(tenantId, employeeId).orElseThrow();
            current.hrSync(sync, clock.instant());
            return employees.save(current);
        });
    }

    public void retry(String tenantId, String sagaId, EmployeeApplicationService.Viewer actor) {
        var saga = Retries.optimistic(() -> {
            var found = sagas.findById(tenantId, sagaId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hire saga " + sagaId + " was not found"));
            found.retry(clock.instant());
            return sagas.save(found);
        });
        audit.record(AuditRecord.of(tenantId, "HIRE_SAGA_RETRIED")
                .user(actor.subject(), null)
                .resource("HireSaga", saga.id())
                .about(saga.offer().candidateId(), saga.applicationId())
                .detail("attempts", saga.attempts())
                .build());
    }
}
