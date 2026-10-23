package com.openhire.applicationservice.service.port;

public interface JobCatalog {
    OpenJob requireOpen(String tenantId, String jobId, String bearerToken);

    record OpenJob(String id, String title, int version) {}
}
