package com.openhire.dispatch.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.openhire.dispatch.service.DispatchService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DeferredDeliverySchedulerTest {
    private final DispatchService dispatch = mock(DispatchService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private DeferredDeliveryScheduler scheduler() {
        when(redis.opsForValue()).thenReturn(values);
        return new DeferredDeliveryScheduler(dispatch, redis);
    }

    @Test
    void redisTimeoutDoesNotSendAndNextSuccessfulRunResumes() {
        var scheduler = scheduler();
        when(values.setIfAbsent(eq("lock:dispatch-release"), any(String.class), eq(Duration.ofSeconds(25))))
                .thenThrow(new QueryTimeoutException("Redis unavailable"))
                .thenReturn(true);
        assertThatCode(scheduler::releaseDeferred).doesNotThrowAnyException();
        verifyNoInteractions(dispatch);
        scheduler.releaseDeferred();
        verify(dispatch).releaseDue();
    }

    @Test
    void anotherOwnerOrUnknownLockResultDoesNotSend() {
        var scheduler = scheduler();
        when(values.setIfAbsent(any(), any(), any(Duration.class)))
                .thenReturn(false)
                .thenReturn((Boolean) null);
        scheduler.releaseDeferred();
        scheduler.releaseDeferred();
        verifyNoInteractions(dispatch);
    }

    @Test
    void storageFailureDuringReleaseIsRetriedByFollowingRun() {
        var scheduler = scheduler();
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(dispatch.releaseDue())
                .thenThrow(new QueryTimeoutException("Store unavailable"))
                .thenReturn(1);
        assertThatCode(scheduler::releaseDeferred).doesNotThrowAnyException();
        scheduler.releaseDeferred();
        verify(dispatch, times(2)).releaseDue();
    }

    @Test
    void programmingErrorsAreNotHiddenAsInfrastructureFailures() {
        var scheduler = scheduler();
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        when(dispatch.releaseDue()).thenThrow(new IllegalStateException("bug"));
        org.assertj.core.api.Assertions.assertThatThrownBy(scheduler::releaseDeferred)
                .isInstanceOf(IllegalStateException.class);
    }
}
