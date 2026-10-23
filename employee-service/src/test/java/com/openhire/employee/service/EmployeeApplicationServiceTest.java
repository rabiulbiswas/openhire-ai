package com.openhire.employee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.PendingEvent;
import com.openhire.employee.domain.EmployeeTypes.RewardStatus;
import com.openhire.employee.service.EmployeeApplicationService.AccessDenied;
import com.openhire.employee.service.EmployeeApplicationService.Viewer;
import com.openhire.employee.service.Memory.Directory;
import com.openhire.employee.service.Memory.Employees;
import com.openhire.employee.service.Memory.MutableClock;
import com.openhire.employee.service.Memory.Numbers;
import com.openhire.employee.service.Memory.Rewards;
import com.openhire.employee.service.Memory.Sagas;
import com.openhire.employee.service.port.EmployeePorts.ApplicationSnapshot;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeApplicationServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T09:00:00Z"));
    private final Employees employees = new Employees();
    private final Sagas sagas = new Sagas();
    private final Rewards rewards = new Rewards();
    private final Directory directory = new Directory();
    private final EmployeeSettings settings = new EmployeeSettings(
            "EMP",
            List.of(Duration.ofSeconds(15)),
            List.of(Duration.ofMinutes(1)),
            Duration.ofHours(24),
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            90);
    private final Viewer hr = new Viewer("hr-1", "hr@x.test", true);
    private final AuditProbe audit = new AuditProbe();
    private HireSagaService saga;
    private EmployeeApplicationService service;

    @BeforeEach
    void setUp() {
        saga = new HireSagaService(
                sagas,
                employees,
                rewards,
                new EmployeeNumberAllocator(new Numbers(), "EMP", clock),
                directory,
                settings,
                clock,
                audit.recorder);
        service = serviceAuditedBy(audit.recorder);
    }

    private EmployeeApplicationService serviceAuditedBy(AuditRecorder recorder) {
        return new EmployeeApplicationService(employees, sagas, rewards, settings, clock, recorder);
    }

    private String hire(String offerId, String applicationId) {
        saga.start(Memory.offer(offerId, applicationId));
        saga.runDue(10);
        saga.applicationHired("t1", applicationId);
        saga.runDue(10);
        return employees.findByOffer("t1", offerId).orElseThrow().id();
    }

    @Test
    void myChecklistMatchesTheTokenEmailIgnoringCase() {
        var id = hire("off-1", "app-1");

        var mine = service.mine("t1", "PRIYA.DAS@example.TEST");

        assertThat(mine.employeeId()).isEqualTo(id);
        assertThat(mine.employeeNo()).isEqualTo("EMP-2026-00001");
        assertThat(mine.tasks()).hasSize(13);
        assertThatThrownBy(() -> service.mine("t1", "someone.else@x.test"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.mine("t1", null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void employeesToggleOnlyTheirOwnTasksWhileHrMayToggleAny() {
        var id = hire("off-1", "app-1");
        var task =
                service.mine("t1", "priya.das@example.test").tasks().getFirst().id();
        var stranger = new Viewer("u-9", "other@x.test", false);
        var owner = new Viewer("u-1", "Priya.Das@example.test", false);

        assertThatThrownBy(() -> service.toggleTask("t1", id, task, true, stranger))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.onboarding("t1", id, stranger)).isInstanceOf(AccessDenied.class);
        service.toggleTask("t1", id, task, true, owner);
        service.toggleTask(
                "t1",
                id,
                service.mine("t1", "priya.das@example.test").tasks().get(1).id(),
                true,
                hr);

        var summary = service.list("t1", null, null).data().getFirst();
        assertThat(summary.onboardingPct()).isEqualTo(15);
        service.toggleMine("t1", "priya.das@example.test", task, false);
        assertThat(service.list("t1", null, null).data().getFirst().onboardingPct())
                .isEqualTo(8);
    }

    @Test
    void listFiltersByStatusAndJoiningDate() {
        hire("off-1", "app-1");

        assertThat(service.list("t1", "ACTIVE", null).data()).isEmpty();
        assertThat(service.list("t1", "pre_boarding", "2026-11-02").data()).hasSize(1);
        assertThat(service.list("t1", null, "2026-11-03").data()).isEmpty();
        assertThat(service.list("other", null, null).data()).isEmpty();
        assertThatThrownBy(() -> service.list("t1", "bogus", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("t1", null, "soon")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailShowsTheSagaStatusAndHrActivationIsOnlyValidOnce() {
        var id = hire("off-1", "app-1");

        assertThat(service.detail("t1", id).sagaStatus()).isEqualTo("COMPLETED");
        assertThat(service.activate("t1", id, hr).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> service.activate("t1", id, hr)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sweepActivatesOnTheJoiningDateOnly() {
        hire("off-1", "app-1");

        service.sweep();
        assertThat(employees.findByOffer("t1", "off-1").orElseThrow().status()).isEqualTo(EmployeeStatus.PRE_BOARDING);
        clock.advance(Duration.ofDays(18));
        service.sweep();

        assertThat(employees.findByOffer("t1", "off-1").orElseThrow().status()).isEqualTo(EmployeeStatus.ACTIVE);
    }

    @Test
    void referralRewardBecomesEligibleOnlyWhenActiveAndAfterTheRewardDays() {
        directory.snapshot = new ApplicationSnapshot("Priya Das", "p@x.test", "", "REFERRAL", "emp-referrer", "OFFER");
        var id = hire("off-1", "app-1");
        service.activate("t1", id, hr);

        clock.advance(Duration.ofDays(60)); // 2026-12-14, joining 2026-11-02 plus 42 days
        service.sweep();
        assertThat(rewards.rows.values().iterator().next().status()).isEqualTo(RewardStatus.PENDING_PROBATION);

        clock.advance(Duration.ofDays(50)); // 2027-02-02 is past 2026-11-02 plus 90 days
        service.sweep();
        service.sweep();

        var reward = rewards.rows.values().iterator().next();
        assertThat(reward.status()).isEqualTo(RewardStatus.ELIGIBLE);
        assertThat(reward.eligibleAt()).isNotNull();
        var eligible = employees.rows.values().iterator().next().pendingEvents().stream()
                .filter(event -> event.eventType().equals("employee.referral-reward.eligible.v1"))
                .toList();
        assertThat(eligible).hasSize(1);
        assertThat(eligible.getFirst().payload()).containsEntry("referrerId", "emp-referrer");
        assertThat(eligible.stream().map(PendingEvent::payload).toString()).doesNotContain("Priya");
        assertThat(service.rewards("t1", "emp-referrer").data()).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo("ELIGIBLE");
            assertThat(view.applicationId()).isEqualTo("app-1");
        });
        assertThat(service.rewards("t1", "someone-else").data()).isEmpty();
    }

    @Test
    void rewardIsNotEligibleWhileTheEmployeeIsStillPreBoardingEvenAfterTheDays() {
        directory.snapshot = new ApplicationSnapshot("Priya Das", "p@x.test", "", "REFERRAL", "emp-referrer", "OFFER");
        hire("off-1", "app-1");
        var employee = employees.findByOffer("t1", "off-1").orElseThrow();

        clock.advance(Duration.ofDays(200));
        // Block the activation sweep so only the reward rule is exercised.
        employees.rows.put(employee.id(), employee);
        var reward = rewards.rows.values().iterator().next();

        assertThat(reward.dueFor(employee, java.time.LocalDate.of(2027, 6, 1), 90))
                .isFalse();
    }

    @Test
    void sagaListFiltersByConsoleStatusAndRetryOfUnknownSagaIsNotFound() {
        hire("off-1", "app-1");

        assertThat(service.sagas("t1", "COMPLETED").data()).hasSize(1);
        assertThat(service.sagas("t1", "FAILED").data()).isEmpty();
        assertThat(service.sagas("t1", null).data().getFirst().id()).isEqualTo("HIRE-off-1");
        assertThatThrownBy(() -> service.sagas("t1", "bogus")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> saga.retry("t1", "HIRE-none", hr)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anHrActivationIsAuditedAsTheActorAndNeverOnAFailedAttempt() {
        var id = hire("off-1", "app-1");

        service.activate("t1", id, hr);
        assertThatThrownBy(() -> service.activate("t1", id, hr)).isInstanceOf(IllegalStateException.class);

        assertThat(audit.only("EMPLOYEE_ACTIVATED"))
                .contains("\"id\":\"hr-1\"", "\"type\":\"USER\"", "\"type\":\"Employee\"", id, "app-1")
                .doesNotContain("Priya", "x.test");
    }

    @Test
    void theJoiningDateSweepAuditsActivationAsTheSystemOnce() {
        hire("off-1", "app-1");
        clock.advance(Duration.ofDays(18));

        service.sweep();
        service.sweep();

        assertThat(audit.only("EMPLOYEE_ACTIVATED"))
                .contains("\"type\":\"SYSTEM\"")
                .doesNotContain("Priya");
    }

    @Test
    void anAuditFailureNeverBreaksActivation() {
        var id = hire("off-1", "app-1");

        var activated = serviceAuditedBy(new AuditProbe().failing().recorder).activate("t1", id, hr);

        assertThat(activated.status()).isEqualTo("ACTIVE");
    }
}
