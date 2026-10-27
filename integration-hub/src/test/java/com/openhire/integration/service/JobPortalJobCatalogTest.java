package com.openhire.integration.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobPortalJobCatalogTest {
    @Test
    void onlyCurrentApprovedOpenVersionCanBePublished() {
        assertTrue(JobPortalJobCatalog.publishable(
                Map.of("status", "OPEN", "approvedVersion", 2, "currentVersion", Map.of("version", 2))));
        for (String status : new String[] {"DRAFT", "PENDING_APPROVAL", "CLOSED", "PAUSED"})
            assertFalse(JobPortalJobCatalog.publishable(
                    Map.of("status", status, "approvedVersion", 2, "currentVersion", Map.of("version", 2))));
        assertFalse(JobPortalJobCatalog.publishable(
                Map.of("status", "OPEN", "approvedVersion", 1, "currentVersion", Map.of("version", 2))));
        assertFalse(JobPortalJobCatalog.publishable(Map.of()));
        assertFalse(JobPortalJobCatalog.publishable(null));
    }
}
