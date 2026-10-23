package com.openhire.offer.scheduler;

import com.openhire.offer.service.OfferApplicationService;
import com.openhire.offer.service.port.OfferPorts.SweepLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires offers past their deadline; the lease lock keeps it to one instance per tick. */
@Component
class OfferSweepScheduler {
    private final OfferApplicationService offers;
    private final SweepLock lock;

    OfferSweepScheduler(OfferApplicationService offers, SweepLock lock) {
        this.offers = offers;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${openhire.offer.sweep-delay:60000}", initialDelay = 15000)
    void sweep() {
        if (lock.tryAcquire("offer-sweep", Duration.ofSeconds(50))) offers.sweep();
    }
}
