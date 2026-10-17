package com.openhire.resumeprocessor;

import java.util.List;

/** Finds the application ids of a candidate; resumes are stored per application and carry no candidate id. */
interface ApplicationLookup {
    List<String> applicationIds(String tenantId, String candidateId);
}
