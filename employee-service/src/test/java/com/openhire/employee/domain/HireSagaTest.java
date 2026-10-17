package com.openhire.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.employee.domain.EmployeeTypes.HrisStatus;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.EmployeeTypes.SagaStatus;
import com.openhire.employee.domain.EmployeeTypes.SagaStep;
import com.openhire.employee.domain.EmployeeTypes.Snapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HireSagaTest {
    private static final Instant T0 = Instant.parse("2026-10-15T09:00:00Z");
    private static final List<Duration> DELAYS =
            List.of(Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(600));
    private static final Snapshot SNAPSHOT = new Snapshot("Priya Das", "priya@example.test", "123", "CAREER", "");

    private static OfferAccepted offer() {
        return new OfferAccepted(
                "t1",
                "off-1",
                "app-1",
                "cand-1",
                "Priya Das",
                "job-1",
                "Engineer",
                "o",
                "2026-11-02",
                "SE",
                "BLR",
                "A");
    }

    private static HireSaga atCreateEmployee() {
        var saga = HireSaga.start(offer(), T0);
        saga.snapshotTaken(SNAPSHOT, T0);
        return saga;
    }

    @Test
    void startsAtSnapshotWithTheOfferIdInTheSagaId() {
        var saga = HireSaga.start(offer(), T0);

        assertThat(saga.id()).isEqualTo("HIRE-off-1");
        assertThat(saga.step()).isEqualTo(SagaStep.SNAPSHOT);
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.due(T0)).isTrue();
    }

    @Test
    void rejectsAnOfferWithoutAUsableJoiningDate() {
        var bad = new OfferAccepted("t1", "off-1", "app-1", "c", "n", "j", "t", "o", "soon", "d", "l", "r");

        assertThatThrownBy(() -> HireSaga.start(bad, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mandatoryStepsRunInOrderAndHrisDoesNotBlockCompletion() {
        var saga = atCreateEmployee();
        assertThat(saga.step()).isEqualTo(SagaStep.CREATE_EMPLOYEE);
        saga.employeeCreated("emp-1", T0);
        assertThat(saga.step()).isEqualTo(SagaStep.UPDATE_APPLICATIONS);
        assertThat(saga.snapshot())
                .as("personal data is dropped once the employee exists")
                .isNull();
        saga.applicationHired(T0);
        assertThat(saga.step()).isEqualTo(SagaStep.ONBOARDING);
        saga.checklistReady(T0);
        assertThat(saga.step()).isEqualTo(SagaStep.HRIS_SYNC);
        saga.hrisRequested(T0);
        assertThat(saga.step()).isEqualTo(SagaStep.COMPLETE);
        saga.complete(T0);

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.REQUESTED);
        assertThat(saga.nextAttemptAt()).isNull();
    }

    @Test
    void applicationHiredBeforeTheWaitingStepIsRemembered() {
        var saga = atCreateEmployee();
        saga.applicationHired(T0);
        assertThat(saga.step()).isEqualTo(SagaStep.CREATE_EMPLOYEE);

        saga.employeeCreated("emp-1", T0);

        assertThat(saga.step()).isEqualTo(SagaStep.ONBOARDING);
    }

    @Test
    void outOfOrderTransitionsAreRejected() {
        var saga = HireSaga.start(offer(), T0);

        assertThatThrownBy(() -> saga.employeeCreated("emp-1", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> saga.complete(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failuresBackOffThenParkTheSagaAtTheSameStep() {
        var saga = HireSaga.start(offer(), T0);

        saga.fail("boom", T0, DELAYS);
        assertThat(saga.nextAttemptAt()).isEqualTo(T0.plusSeconds(15));
        assertThat(saga.due(T0)).isFalse();
        saga.fail("boom", T0, DELAYS);
        assertThat(saga.nextAttemptAt()).isEqualTo(T0.plusSeconds(60));
        saga.fail("boom", T0, DELAYS);
        assertThat(saga.nextAttemptAt()).isEqualTo(T0.plusSeconds(600));
        saga.fail("boom", T0, DELAYS);

        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.stuck()).isTrue();
        assertThat(saga.nextAttemptAt()).isNull();
        assertThat(saga.step()).isEqualTo(SagaStep.SNAPSHOT);
        assertThat(saga.lastError()).isEqualTo("boom");
    }

    @Test
    void manualRetryResumesAParkedSagaAtItsStoredStep() {
        var saga = atCreateEmployee();
        for (int i = 0; i < 4; i++) saga.fail("db down", T0, DELAYS);

        saga.retry(T0.plusSeconds(5));

        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.step()).isEqualTo(SagaStep.CREATE_EMPLOYEE);
        assertThat(saga.attempts()).isZero();
        assertThat(saga.stuck()).isFalse();
        assertThat(saga.due(T0.plusSeconds(5))).isTrue();
    }

    @Test
    void waitingForTheApplicationFlagsTheSagaStuckAfterTheThreshold() {
        var saga = atCreateEmployee();
        saga.employeeCreated("emp-1", T0);
        var poll = Duration.ofSeconds(30);
        var stuckAfter = Duration.ofHours(24);

        saga.waitForApplication(T0.plusSeconds(60), poll, stuckAfter);
        assertThat(saga.stuck()).isFalse();
        saga.waitForApplication(T0.plus(Duration.ofHours(24)), poll, stuckAfter);

        assertThat(saga.stuck()).isTrue();
        assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
    }

    @Test
    void aLeaseKeepsOtherWorkersOutUntilItExpires() {
        var saga = HireSaga.start(offer(), T0);

        saga.claim(T0, Duration.ofSeconds(60), "worker-a");

        assertThat(saga.due(T0.plusSeconds(30))).isFalse();
        assertThat(saga.due(T0.plusSeconds(61))).isTrue();
        saga.release(T0.plusSeconds(10));
        assertThat(saga.due(T0.plusSeconds(10))).isTrue();
    }

    @Test
    void hrisFailuresRetryThenHandOverToHrOpsWithoutTouchingTheSagaStatus() {
        var delays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1));
        var saga = completed();

        for (int expected = 0; expected < 3; expected++) {
            assertThat(saga.hrisFailed(T0, delays)).isTrue();
            assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.RETRYING);
            assertThat(saga.nextAttemptAt()).isEqualTo(T0.plus(delays.get(expected)));
            saga.hrisRequested(T0);
        }
        assertThat(saga.hrisFailed(T0, delays)).isFalse();

        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.COMPENSATE_PENDING);
        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.nextAttemptAt()).isNull();
    }

    @Test
    void hrisSyncedIsFinalAndIdempotent() {
        var saga = completed();

        saga.hrisSynced(T0);
        saga.hrisSynced(T0.plusSeconds(1));
        saga.hrisFailed(T0.plusSeconds(2), DELAYS);

        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.SYNCED);
    }

    @Test
    void retryOfACompletedSagaIsRefusedUnlessTheHrisHandOffFailed() {
        var saga = completed();
        assertThatThrownBy(() -> saga.retry(T0)).isInstanceOf(IllegalStateException.class);

        for (int i = 0; i < 3; i++) {
            saga.hrisFailed(T0, DELAYS);
            saga.hrisRequested(T0);
        }
        saga.hrisFailed(T0, DELAYS);
        saga.retry(T0);

        assertThat(saga.hrisStatus()).isEqualTo(HrisStatus.RETRYING);
        assertThat(saga.due(T0)).isTrue();
    }

    private static HireSaga completed() {
        var saga = atCreateEmployee();
        saga.employeeCreated("emp-1", T0);
        saga.applicationHired(T0);
        saga.checklistReady(T0);
        saga.hrisRequested(T0);
        saga.complete(T0);
        return saga;
    }
}
