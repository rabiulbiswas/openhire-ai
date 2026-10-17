package com.openhire.employee.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.employee.application.EmployeeApplicationService.Viewer;
import com.openhire.employee.application.Memory.Directory;
import com.openhire.employee.application.Memory.Employees;
import com.openhire.employee.application.Memory.MutableClock;
import com.openhire.employee.application.Memory.Numbers;
import com.openhire.employee.application.Memory.Rewards;
import com.openhire.employee.application.Memory.Sagas;
import com.openhire.employee.application.port.EmployeePorts.ApplicationSnapshot;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.HrSync;
import com.openhire.employee.domain.EmployeeTypes.HrisStatus;
import com.openhire.employee.domain.EmployeeTypes.PendingEvent;
import com.openhire.employee.domain.EmployeeTypes.RewardStatus;
import com.openhire.employee.domain.EmployeeTypes.SagaStatus;
import com.openhire.employee.domain.EmployeeTypes.SagaStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class HireSagaServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T09:00:00Z"));
    private final Employees employees = new Employees();
    private final Sagas sagas = new Sagas();
    private final Rewards rewards = new Rewards();
    private final Directory directory = new Directory();
    private final EmployeeSettings settings = new EmployeeSettings(
            "EMP",
            List.of(Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(600)),
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1)),
            Duration.ofHours(24),
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            90);
    private final AuditProbe audit = new AuditProbe();
    private HireSagaService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    private HireSagaService newService() {
        var numbers = new EmployeeNumberAllocator(new Numbers(), "EMP", clock);
        return new HireSagaService(sagas, employees, rewards, numbers, directory, settings, clock, audit.recorder);
    }

    private void run() {
        service.runDue(10);
    }

    private List<String> eventTypes() {
        return employees.rows.values().stream()
                .flatMap(row -> row.pendingEvents().stream())
                .map(PendingEvent::eventType)
                .toList();
    }

    private void hireApplication() {
        service.applicationHired("t1", "app-1");
        run();
    }

    @Test
    void happyPathCreatesTheEmployeeWaitsForTheApplicationThenCompletes() {
        service.start(Memory.offer("off-1", "app-1"));
        run();

        var saga = sagas.rows.get("HIRE-off-1");
        assertThat(saga.step()).isEqualTo(SagaStep.UPDATE_APPLICATIONS);
        var employee = employees.findByOffer("t1", "off-1").orElseThrow();
        assertThat(employee.employeeNo()).isEqualTo("EMP-2026-00001");
        assertThat(employee.status()).isEqualTo(EmployeeStatus.PRE_BOARDING);
        assertThat(employee.email()).isEqualTo("priya.das@example.test");
        assertThat(eventTypes()).containsExactly("employee.created.v1");

        hireApplication();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.REQUESTED);
        assertThat(employee.tasks()).isNotEmpty();
        assertThat(eventTypes()).containsExactly("employee.created.v1", "employee.hris-sync.requested.v1");
    }

    @Test
    void duplicateOfferAcceptedKeepsOneSagaOneEmployeeAndOneNumber() {
        service.start(Memory.offer("off-1", "app-1"));
        service.start(Memory.offer("off-1", "app-1"));
        run();
        service.start(Memory.offer("off-1", "app-1"));
        run();

        assertThat(sagas.rows).hasSize(1);
        assertThat(employees.rows).hasSize(1);
        assertThat(eventTypes()).containsOnlyOnce("employee.created.v1");
    }

    @Test
    void snapshotFailuresRetryWithBackOffAndThenResume() {
        directory.failures = 2;
        service.start(Memory.offer("off-1", "app-1"));

        run();
        var saga = sagas.rows.get("HIRE-off-1");
        assertThat(saga.step()).isEqualTo(SagaStep.SNAPSHOT);
        assertThat(saga.attempts()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(10));
        run();
        assertThat(saga.attempts()).as("not due before the back-off elapses").isEqualTo(1);
        clock.advance(Duration.ofSeconds(10));
        run();
        assertThat(saga.attempts()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(61));
        run();

        assertThat(saga.step()).isEqualTo(SagaStep.UPDATE_APPLICATIONS);
        assertThat(employees.rows).hasSize(1);
    }

    @Test
    void exhaustedRetriesParkTheSagaAndAnOperatorRetryResumesIt() {
        directory.failures = 100;
        service.start(Memory.offer("off-1", "app-1"));
        for (int i = 0; i < 4; i++) {
            run();
            clock.advance(Duration.ofMinutes(11));
        }

        var saga = sagas.rows.get("HIRE-off-1");
        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.stuck()).isTrue();
        assertThat(saga.lastError()).contains("application-service unavailable");

        directory.failures = 0;
        service.retry("t1", "HIRE-off-1", new Viewer("op-1", "op@x.test", true));
        run();

        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.step()).isEqualTo(SagaStep.UPDATE_APPLICATIONS);
        assertThat(audit.only("HIRE_SAGA_RETRIED"))
                .contains("\"id\":\"op-1\"", "\"type\":\"HireSaga\"", "HIRE-off-1", "app-1")
                .doesNotContain("Priya", "op@x.test");
    }

    @Test
    void partialFailureAfterNumberAllocationReusesTheNumberOnResume() {
        employees.failNextSave = () -> new DataAccessResourceFailureException("mongo down");
        service.start(Memory.offer("off-1", "app-1"));

        run();
        var saga = sagas.rows.get("HIRE-off-1");
        assertThat(saga.step()).isEqualTo(SagaStep.CREATE_EMPLOYEE);
        assertThat(saga.employeeNo()).isEqualTo("EMP-2026-00001");
        assertThat(employees.rows).isEmpty();
        clock.advance(Duration.ofSeconds(20));
        run();

        assertThat(employees.findByOffer("t1", "off-1").orElseThrow().employeeNo())
                .isEqualTo("EMP-2026-00001");
        assertThat(saga.step()).isEqualTo(SagaStep.UPDATE_APPLICATIONS);
    }

    @Test
    void aNewInstanceResumesFromTheStoredStepAfterARestart() {
        service.start(Memory.offer("off-1", "app-1"));
        run();
        var restarted = newService();

        restarted.applicationHired("t1", "app-1");
        restarted.runDue(10);

        assertThat(sagas.rows.get("HIRE-off-1").status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(employees.rows).hasSize(1);
        assertThat(eventTypes()).containsOnlyOnce("employee.created.v1");
    }

    @Test
    void aSagaLeasedByAnotherWorkerIsNotRunUntilTheLeaseExpires() {
        service.start(Memory.offer("off-1", "app-1"));
        sagas.claimNext(clock.instant(), Duration.ofSeconds(60), "other-instance");

        assertThat(service.runDue(10)).isZero();
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.runDue(10)).isEqualTo(1);
    }

    @Test
    void aMissedStageEventIsCoveredByPollingTheApplication() {
        service.start(Memory.offer("off-1", "app-1"));
        run();
        directory.snapshot = new ApplicationSnapshot("Priya Das", "p@x.test", "", "CAREER", "", "HIRED");
        clock.advance(Duration.ofSeconds(31));

        run();

        assertThat(sagas.rows.get("HIRE-off-1").status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void waitingLongerThanTheThresholdFlagsTheSagaStuckButItStillCompletesLater() {
        service.start(Memory.offer("off-1", "app-1"));
        run();
        clock.advance(Duration.ofHours(25));
        run();
        var saga = sagas.rows.get("HIRE-off-1");
        assertThat(saga.stuck()).isTrue();

        hireApplication();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.stuck()).isFalse();
    }

    @Test
    void hrisOutcomesUpdateTheSagaAndTheEmployeeWithoutBlockingCompletion() {
        service.start(Memory.offer("off-1", "app-1"));
        run();
        hireApplication();
        var employee = employees.findByOffer("t1", "off-1").orElseThrow();
        var saga = sagas.rows.get("HIRE-off-1");

        service.hrisOutcome("t1", employee.id(), false);
        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.RETRYING);
        assertThat(employee.hrSync()).isEqualTo(HrSync.RETRYING);
        clock.advance(Duration.ofMinutes(2));
        run();
        assertThat(saga.hrisRequests()).isEqualTo(2);
        assertThat(eventTypes())
                .filteredOn("employee.hris-sync.requested.v1"::equals)
                .hasSize(2);

        service.hrisOutcome("t1", employee.id(), true);
        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.SYNCED);
        assertThat(employee.hrSync()).isEqualTo(HrSync.SYNCED);
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void referralHireCreatesOnePendingRewardForTheReferrer() {
        directory.snapshot = new ApplicationSnapshot("Priya Das", "p@x.test", "", "REFERRAL", "emp-referrer", "OFFER");
        service.start(Memory.offer("off-1", "app-1"));
        run();
        clock.advance(Duration.ofSeconds(20));
        run();

        assertThat(rewards.rows).hasSize(1);
        var reward = rewards.rows.values().iterator().next();
        assertThat(reward.referrerId()).isEqualTo("emp-referrer");
        assertThat(reward.status()).isEqualTo(RewardStatus.PENDING_PROBATION);
        assertThat(reward.joiningDate()).isEqualTo("2026-11-02");
    }

    @Test
    void nonReferralHireCreatesNoReward() {
        directory.snapshot = new ApplicationSnapshot("Priya Das", "p@x.test", "", "AGENCY", "agency-1", "OFFER");
        service.start(Memory.offer("off-1", "app-1"));
        run();

        assertThat(rewards.rows).isEmpty();
    }
}
