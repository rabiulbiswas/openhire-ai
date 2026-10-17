package com.openhire.applicationservice.application;

import com.openhire.applicationservice.application.port.ApplicationStore;
import com.openhire.applicationservice.application.port.JobCatalog;
import com.openhire.applicationservice.domain.ApplicationRecord;
import com.openhire.applicationservice.domain.ApplicationStage;
import com.openhire.applicationservice.domain.IntakeCommand;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {
    private final ApplicationStore store;
    private final JobCatalog jobs;
    private final Clock clock;

    public IntakeService(ApplicationStore store, JobCatalog jobs, Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.clock = clock;
    }

    public ApplicationRecord submit(String tenant, String actor, String key, IntakeCommand command, String bearer) {
        key = IntakeCommand.required(key, "Idempotency-Key", 200);
        var id = IntakeCommand.digest(tenant, actor, key);
        var existing = store.find(tenant, id);
        // Replays must work even if a job closed after the original submission.
        if (existing.isPresent()) return sameRequest(existing.get(), command);
        var job = jobs.requireOpen(tenant, command.jobId(), bearer);
        var record = ApplicationRecord.submit(id, tenant, actor, command, job.title(), job.version(), clock.instant());
        return sameRequest(store.insertOrFind(record), command);
    }

    public ApplicationRecord get(String tenant, String id) {
        return store.find(tenant, id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }

    public List<ApplicationRecord> list(String tenant, String jobId, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be 0..10000 and size 1..100");
        }
        return store.list(tenant, jobId, page, size);
    }

    public ApplicationRecord transition(
            String tenant, String actor, String id, long revision, ApplicationStage stage, String reason) {
        var record = get(tenant, id);
        if (record.revision() == null || record.revision() != revision) {
            throw new OptimisticLockingFailureException("Reload the application before editing");
        }
        return store.save(record.transition(stage, actor, reason, clock.instant()));
    }

    private ApplicationRecord sameRequest(ApplicationRecord record, IntakeCommand command) {
        if (!record.requestFingerprint().equals(command.fingerprint())) {
            throw new IllegalStateException("Idempotency-Key was already used with a different request");
        }
        return record;
    }
}
