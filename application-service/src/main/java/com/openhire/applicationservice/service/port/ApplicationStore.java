package com.openhire.applicationservice.service.port;

import com.openhire.applicationservice.domain.ApplicationRecord;
import java.util.List;
import java.util.Optional;

public interface ApplicationStore {
    Optional<ApplicationRecord> find(String tenantId, String id);

    ApplicationRecord insertOrFind(ApplicationRecord record);

    ApplicationRecord save(ApplicationRecord record);

    List<ApplicationRecord> list(String tenantId, String jobId, int page, int size);
}
