package com.openhire.applicationservice.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.applicationservice.repository.mongo.JobMetricsRepository;
import com.openhire.support.Access;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobMetricsControllerTest {
    private final JobMetricsRepository metrics = mock(JobMetricsRepository.class);
    private final JobMetricsController controller =
            new JobMetricsController(new Access("oidc", "", "test-secret"), metrics);

    @Test
    void requiresServiceCredentialsAndNonEmptyTenantAndBoundsTheBatch() {
        assertThatThrownBy(() -> controller.counts("wrong", "tenant-a", List.of("job")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.counts("test-secret", " ", List.of("job")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(
                        () -> controller.counts("test-secret", "tenant-a", java.util.Collections.nCopies(201, "job")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(metrics);
    }

    @Test
    void passesOnlyTheRequestedTenantAndJobs() {
        when(metrics.counts("tenant-a", List.of("job")))
                .thenReturn(List.of(new JobMetricsRepository.Counts("job", 8, 2)));
        assertThat(controller.counts("test-secret", "tenant-a", List.of("job")))
                .containsExactly(new JobMetricsRepository.Counts("job", 8, 2));
    }
}
