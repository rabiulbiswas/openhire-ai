package com.openhire.applicationservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.domain.ApplicationRecord;
import com.openhire.applicationservice.domain.ApplicationStage;
import com.openhire.applicationservice.domain.IntakeCommand;
import com.openhire.applicationservice.service.port.ApplicationStore;
import com.openhire.applicationservice.service.port.JobCatalog;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class IntakeServiceTest {
    private final ApplicationStore store = mock(ApplicationStore.class);
    private final JobCatalog jobs = mock(JobCatalog.class);
    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final IntakeService service = new IntakeService(store, jobs, Clock.fixed(now, ZoneOffset.UTC));
    private final IntakeCommand command =
            new IntakeCommand("job-1", "Jane Doe", "Jane@example.test", "", true, "v1", "");

    @Test
    void acceptedIntakeCapturesConsentJobVersionAndEventTogether() {
        when(store.find(any(), any())).thenReturn(Optional.empty());
        when(jobs.requireOpen("org-1", "job-1", "jwt")).thenReturn(new JobCatalog.OpenJob("job-1", "Engineer", 3));
        when(store.insertOrFind(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.submit("org-1", "recruiter", "key", command, "jwt");
        assertThat(result.intake().email()).isEqualTo("jane@example.test");
        assertThat(result.jobVersion()).isEqualTo(3);
        assertThat(result.submittedBy()).isEqualTo("recruiter");
        assertThat(result.stage()).isEqualTo(ApplicationStage.APPLIED);
        assertThat(result.pendingEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("ApplicationSubmitted");
            assertThat(event.tenantId()).isEqualTo("org-1");
            assertThat(event.payload()).containsEntry("consentVersion", "v1");
        });
    }

    @Test
    void unavailableOrClosedJobCannotPersistAnApplication() {
        when(store.find(any(), any())).thenReturn(Optional.empty());
        when(jobs.requireOpen(any(), any(), any())).thenThrow(new IllegalStateException("Job is closed"));
        assertThatThrownBy(() -> service.submit("org-1", "recruiter", "key", command, "jwt"))
                .isInstanceOf(IllegalStateException.class);
        verify(store, never()).insertOrFind(any());
    }

    @Test
    void replayDoesNotDependOnJobStillBeingOpen() {
        var existing = record("org-1", "key");
        when(store.find("org-1", existing.id())).thenReturn(Optional.of(existing));
        assertThat(service.submit("org-1", "recruiter", "key", command, "jwt")).isEqualTo(existing);
        verifyNoInteractions(jobs);
        verify(store, never()).insertOrFind(any());
    }

    @Test
    void changedPayloadWithSameKeyIsRejected() {
        var existing = record("org-1", "key");
        when(store.find("org-1", existing.id())).thenReturn(Optional.of(existing));
        var changed = new IntakeCommand("job-1", "Someone Else", "other@example.test", "", true, "v1", "");
        assertThatThrownBy(() -> service.submit("org-1", "recruiter", "key", changed, "jwt"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentInsertWinnerIsCheckedBeforeReturning() {
        when(store.find(any(), any())).thenReturn(Optional.empty());
        when(jobs.requireOpen(any(), any(), any())).thenReturn(new JobCatalog.OpenJob("job-1", "Engineer", 3));
        var winner = record("org-1", "key");
        when(store.insertOrFind(any())).thenReturn(winner);
        assertThat(service.submit("org-1", "recruiter", "key", command, "jwt")).isSameAs(winner);
    }

    @Test
    void lookupUsesTenantAndKeysAreScopedByTenantAndActor() {
        var one = record("org-1", "key");
        var two = record("org-2", "key");
        assertThat(one.id()).isNotEqualTo(two.id());
        assertThat(one.id()).isNotEqualTo(IntakeCommand.digest("org-1", "another-actor", "key"));
        when(store.find("org-2", one.id())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("org-2", one.id())).isInstanceOf(ResourceNotFoundException.class);
        verify(store).find("org-2", one.id());
    }

    @Test
    void staleRevisionCannotMutateApplication() {
        var record = record("org-1", "key");
        when(store.find("org-1", record.id())).thenReturn(Optional.of(record));
        assertThatThrownBy(() -> service.transition(
                        "org-1", "recruiter", record.id(), 99, ApplicationStage.SHORTLISTED, "Reviewed"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        verify(store, never()).save(any());
    }

    @Test
    void decisionsRequireReasonsAndTerminalStatesCannotBeReopened() {
        var application = record("org-1", "key");
        assertThatThrownBy(() -> application.transition(ApplicationStage.REJECTED, "actor", "", now))
                .isInstanceOf(IllegalArgumentException.class);
        var rejected = application.transition(ApplicationStage.REJECTED, "actor", "Requirements not met", now);
        assertThat(rejected.decisions()).hasSize(1);
        assertThat(rejected.pendingEvents()).hasSize(2);
        assertThatThrownBy(() -> rejected.transition(ApplicationStage.SHORTLISTED, "actor", "Reopen", now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void intakeWithoutConsentAndOversizedPagesAreRejected() {
        assertThatThrownBy(() -> new IntakeCommand("job", "Jane", "jane@example.test", "", false, "v1", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("org-1", null, 0, 1000)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    private ApplicationRecord record(String tenant, String key) {
        return ApplicationRecord.submit(
                IntakeCommand.digest(tenant, "recruiter", key), tenant, "recruiter", command, "Engineer", 3, now);
    }
}
