package com.openhire.applicationservice.adapter.out.mongo;

import com.openhire.applicationservice.domain.ApplicationRecord;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("applications")
@CompoundIndex(name = "tenant_job_submitted", def = "{'tenantId':1,'jobId':1,'submittedAt':-1,'_id':1}")
public record ApplicationDocument(
        @Id String id,
        String tenantId,
        String jobId,
        Instant submittedAt,
        @Version Long revision,
        ApplicationRecord data) {
    public static ApplicationDocument from(ApplicationRecord record) {
        return new ApplicationDocument(
                record.id(),
                record.tenantId(),
                record.intake().jobId(),
                record.submittedAt(),
                record.revision(),
                record);
    }

    public ApplicationRecord toDomain() {
        return new ApplicationRecord(
                id,
                tenantId,
                data.submittedBy(),
                data.requestFingerprint(),
                data.intake(),
                data.jobTitle(),
                data.jobVersion(),
                data.stage(),
                submittedAt,
                data.updatedAt(),
                revision,
                data.decisions(),
                data.pendingEvents());
    }
}
