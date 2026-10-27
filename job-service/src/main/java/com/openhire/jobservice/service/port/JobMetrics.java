package com.openhire.jobservice.service.port;

import java.util.List;
import java.util.Map;

public interface JobMetrics {
    Map<String, Counts> counts(String tenantId, List<String> jobIds);

    record Counts(int applicants, int shortlisted) {}
}
