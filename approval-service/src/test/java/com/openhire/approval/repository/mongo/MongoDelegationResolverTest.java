package com.openhire.approval.repository.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openhire.approval.domain.DelegationPeriod;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MongoDelegationResolverTest {
    private final DelegationPeriodRepository periods = mock(DelegationPeriodRepository.class);
    private final MongoDelegationResolver resolver = new MongoDelegationResolver(periods);

    @Test
    void appliesAnActiveDelegationPeriod() {
        var now = Instant.now();
        var period = DelegationPeriod.create(
                "tenant-a", "manager", "delegate", now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS));
        when(periods.findFirstByTenantIdAndOwnerIdAndStartsAtLessThanEqualAndEndsAtGreaterThanEqualOrderByStartsAtDesc(
                        "tenant-a", "manager", now, now))
                .thenReturn(Optional.of(period));

        assertThat(resolver.resolve("tenant-a", "manager", now)).isEqualTo("delegate");
    }
}
