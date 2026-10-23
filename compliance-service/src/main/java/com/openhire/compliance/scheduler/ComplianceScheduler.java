package com.openhire.compliance.scheduler;

import com.openhire.compliance.service.FairnessService;
import com.openhire.compliance.service.PrivacyService;
import com.openhire.compliance.service.RetentionService;
import com.openhire.compliance.service.port.CompliancePorts.SweepLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each job takes a lease lock first so only one instance runs it. */
@Component
class ComplianceScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ComplianceScheduler.class);
    private final PrivacyService privacy;
    private final RetentionService retention;
    private final FairnessService fairness;
    private final SweepLock lock;

    ComplianceScheduler(PrivacyService privacy, RetentionService retention, FairnessService fairness, SweepLock lock) {
        this.privacy = privacy;
        this.retention = retention;
        this.fairness = fairness;
        this.lock = lock;
    }

    /** Flags stuck requests and resumes erasures whose legal hold ended. */
    @Scheduled(fixedDelayString = "${openhire.compliance.sweep-delay:30000}", initialDelay = 20000)
    void sweep() {
        if (lock.tryAcquire("compliance-sweep", Duration.ofSeconds(25))) privacy.sweep();
    }

    @Scheduled(cron = "${openhire.compliance.retention-cron:0 30 2 * * *}", zone = "UTC")
    void retention() {
        if (!lock.tryAcquire("compliance-retention", Duration.ofHours(2))) return;
        retention.runAll();
        LOG.info("Daily retention run finished");
    }

    @Scheduled(cron = "${openhire.compliance.fairness-cron:0 0 3 * * MON}", zone = "UTC")
    void fairness() {
        if (!lock.tryAcquire("compliance-fairness", Duration.ofHours(2))) return;
        LOG.info("Weekly fairness refresh raised {} alerts", fairness.refreshAll());
    }
}
