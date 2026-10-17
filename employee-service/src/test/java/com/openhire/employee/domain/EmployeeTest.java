package com.openhire.employee.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.EmployeeTypes.Snapshot;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmployeeTest {
    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    private static Employee employee() {
        var offer = new OfferAccepted(
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
        return Employee.create(
                offer,
                new Snapshot("Priya Das", " Priya@Example.TEST ", "+91 99999 00000", "CAREER", ""),
                "EMP-2026-00001",
                NOW);
    }

    @Test
    void createdEventCarriesIdsNumberDateAndDesignationButNoPersonalData() {
        var event = employee().pendingEvents().getFirst();

        assertThat(event.eventType()).isEqualTo("employee.created.v1");
        assertThat(event.correlationId()).isEqualTo("app-1");
        assertThat(event.payload())
                .containsKeys("employeeId", "employeeNo", "applicationId", "candidateId", "jobId", "offerId")
                .containsEntry("joiningDate", "2026-11-02")
                .containsEntry("designation", "SE");
        assertThat(event.payload().toString()).doesNotContain("Priya", "example", "99999");
    }

    @Test
    void emailIsNormalisedAndTheEmployeeStartsPreBoarding() {
        var employee = employee();

        assertThat(employee.email()).isEqualTo("priya@example.test");
        assertThat(employee.status()).isEqualTo(EmployeeStatus.PRE_BOARDING);
    }

    @Test
    void checklistIsBuiltOnceFromTheDefaultTemplateInFourGroups() {
        var employee = employee();

        assertThat(employee.createChecklist(OnboardingTemplate.standard(), NOW)).isTrue();
        assertThat(employee.createChecklist(OnboardingTemplate.standard(), NOW)).isFalse();
        assertThat(employee.tasks().stream()
                        .map(EmployeeTypes.OnboardingTask::group)
                        .distinct())
                .containsExactly("Documents", "IT access", "Buddy and HR", "First week");
    }

    @Test
    void percentageRoundsAndCompletionIsEmittedOnce() {
        var employee = employee();
        employee.createChecklist(OnboardingTemplate.standard(), NOW);
        var tasks = employee.tasks();

        employee.setTask(tasks.getFirst().id(), true, NOW);
        assertThat(employee.onboardingPct()).isEqualTo(8);
        tasks.forEach(task -> employee.setTask(task.id(), true, NOW));
        employee.setTask(tasks.getFirst().id(), false, NOW);
        employee.setTask(tasks.getFirst().id(), true, NOW);

        assertThat(employee.onboardingPct()).isEqualTo(100);
        assertThat(employee.pendingEvents())
                .extracting(EmployeeTypes.PendingEvent::eventType)
                .containsOnlyOnce("employee.onboarding.completed.v1");
    }

    @Test
    void unknownTaskIsNotFound() {
        assertThatThrownBy(() -> employee().setTask("nope", true, NOW)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activationOnlyMovesPreBoardingToActive() {
        var employee = employee();

        employee.activate(NOW);

        assertThat(employee.status()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThatThrownBy(() -> employee.activate(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hrisRequestForTheSameAttemptIsEmittedOnce() {
        var employee = employee();

        assertThat(employee.requestHrisSync(1, NOW)).isTrue();
        assertThat(employee.requestHrisSync(1, NOW)).isFalse();
        assertThat(employee.requestHrisSync(2, NOW)).isTrue();
    }
}
