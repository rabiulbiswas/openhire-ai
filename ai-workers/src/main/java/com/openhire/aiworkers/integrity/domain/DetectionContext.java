package com.openhire.aiworkers.integrity.domain;

import java.util.List;
import java.util.function.Supplier;

/** Everything a detector may look at. {@code roles} is lazy because it costs a model call. */
public record DetectionContext(
        String tenantId,
        String applicationId,
        String candidateId,
        String resumeId,
        String candidateName,
        String candidateEmail,
        String candidatePhone,
        String text,
        Supplier<List<Role>> roles) {}
