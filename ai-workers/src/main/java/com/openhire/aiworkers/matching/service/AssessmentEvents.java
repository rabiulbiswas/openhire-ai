package com.openhire.aiworkers.matching.service;

import com.openhire.aiworkers.matching.domain.Assessment;
import com.openhire.aiworkers.shared.service.Envelopes;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the {@code assessment.completed.v1} envelope for one assessment version. */
final class AssessmentEvents {
    private AssessmentEvents() {}

    static Map<String, Object> completed(Assessment a) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("applicationId", a.applicationId());
        payload.put("candidateId", a.candidateId());
        payload.put("assessmentId", a.id());
        payload.put("resumeId", a.resumeId());
        payload.put("status", a.status());
        payload.put("score", a.score());
        payload.put("confidence", a.confidence());
        payload.put("jobId", a.jobId());
        payload.put("jobVersion", a.jobVersion());
        payload.put("rubricVersion", String.valueOf(a.jobVersion()));
        payload.put(
                "criteria",
                a.criteria().stream()
                        .map(c -> {
                            var row = new LinkedHashMap<String, Object>();
                            row.put("id", c.id());
                            row.put("label", c.label());
                            row.put("requirement", c.requirement());
                            row.put("weight", c.weight());
                            row.put("score", c.score());
                            row.put("evidence", c.evidence());
                            row.put("grounded", c.grounded());
                            return (Object) row;
                        })
                        .toList());
        payload.put("strengths", a.strengths());
        payload.put("gapsToProbe", a.gapsToProbe());
        payload.put("hardGaps", a.hardGaps());
        if (!a.failedRules().isEmpty()) payload.put("failedRules", a.failedRules());
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("model", a.provenance().model());
        provenance.put("promptKey", a.provenance().promptKey());
        provenance.put("promptVersion", a.provenance().promptVersion());
        provenance.put("rubricVersion", String.valueOf(a.jobVersion()));
        provenance.put("createdAt", a.createdAt().toString());
        provenance.put("traceId", a.provenance().traceId());
        payload.put("provenance", provenance);
        payload.put("label", a.label());
        return Envelopes.event("assessment.completed.v1", a.tenantId(), "Assessment", a.applicationId(), payload);
    }
}
