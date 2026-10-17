package com.openhire.aigateway.application.port;

import java.time.Instant;
import java.util.List;

/** Owned by AI gateway; never exposes another service's database. */
public interface LogRetention {
    List<String> tenants();

    long purge(String tenant, Instant before, boolean dryRun, int limit);
}
