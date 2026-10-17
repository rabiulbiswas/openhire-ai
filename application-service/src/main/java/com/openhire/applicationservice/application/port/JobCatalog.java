package com.openhire.applicationservice.application.port;

public interface JobCatalog {
    OpenJob requireOpen(String tenantId, String jobId, String bearerToken);

    record OpenJob(String id, String title, int version) {}
}
