package com.openhire.talent.adapter.in.schedule;

import com.openhire.talent.application.ProfileProjector;
import com.openhire.talent.application.port.TalentPorts.SweepLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily retention sweep and the embedding retry, each behind a lease lock so one instance runs them. */
@Component
class TalentScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(TalentScheduler.class);
    private final ProfileProjector projector;
    private final SweepLock lock;

    TalentScheduler(ProfileProjector projector, SweepLock lock) {
        this.projector = projector;
        this.lock = lock;
    }

    @Scheduled(cron = "${openhire.talent.sweep-cron:0 30 2 * * *}")
    void retention() {
        if (!lock.tryAcquire("talent-retention-sweep", Duration.ofMinutes(30))) return;
        int expired = projector.sweepRetention();
        if (expired > 0) LOG.info("Retention sweep expired {} talent profiles", expired);
    }

    @Scheduled(fixedDelayString = "${openhire.talent.embedding-retry-delay:300000}", initialDelay = 60000)
    void embeddings() {
        if (!lock.tryAcquire("talent-embedding-retry", Duration.ofMinutes(4))) return;
        int done = projector.retryEmbeddings();
        if (done > 0) LOG.info("Embedded {} talent profiles that were waiting for the model", done);
    }
}
