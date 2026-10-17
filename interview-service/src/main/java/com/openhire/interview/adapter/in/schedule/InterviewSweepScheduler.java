package com.openhire.interview.adapter.in.schedule;

import com.openhire.interview.application.InterviewApplicationService;
import com.openhire.interview.application.port.InterviewPorts.SweepLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Completes ended rounds and flags overdue feedback; the lease lock keeps it to one instance per tick. */
@Component
class InterviewSweepScheduler {
    private final InterviewApplicationService interviews;
    private final SweepLock lock;

    InterviewSweepScheduler(InterviewApplicationService interviews, SweepLock lock) {
        this.interviews = interviews;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${openhire.interview.sweep-delay:60000}", initialDelay = 15000)
    void sweep() {
        if (lock.tryAcquire("interview-sweep", Duration.ofSeconds(50))) interviews.sweep();
    }
}
