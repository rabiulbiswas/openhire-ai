package com.openhire.compliance.repository.mongo;

import com.openhire.compliance.service.port.CompliancePorts.Erased;
import com.openhire.support.privacy.Tombstones;
import org.springframework.stereotype.Component;

/** Uses the shared tombstone collection; application ids are stored with an {@code app:} prefix. */
@Component
class TombstoneErased implements Erased {
    private final Tombstones tombstones;

    TombstoneErased(Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    @Override
    public boolean isErased(String tenantId, String id) {
        return tombstones.isErased(tenantId, id);
    }

    @Override
    public void mark(String tenantId, String id, String requestId) {
        tombstones.mark(tenantId, id, requestId);
    }
}
