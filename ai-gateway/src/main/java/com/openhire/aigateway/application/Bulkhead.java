package com.openhire.aigateway.application;

import com.openhire.aigateway.application.AiExceptions.AiUnavailable;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Caps concurrent calls to one provider; callers wait a bounded time for a slot, then fail soft. */
public class Bulkhead {
    private final Semaphore permits;
    private final Duration wait;

    public Bulkhead(int maxConcurrent, Duration wait) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.wait = wait;
    }

    public Permit acquire() {
        try {
            if (!permits.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS))
                throw new AiUnavailable("The model server is busy; try again shortly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailable("Interrupted while waiting for the model server");
        }
        return new Permit(permits);
    }

    public int available() {
        return permits.availablePermits();
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private boolean released;

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                permits.release();
            }
        }
    }
}
