package com.openhire.aiworkers.integrity.domain;

import java.util.Optional;

/** One independent, advisory check. A signal is raised only when the confidence reaches {@link #threshold()}. */
public interface Detector {
    String type();

    double threshold();

    default boolean enabled() {
        return true;
    }

    Optional<Finding> detect(DetectionContext context);
}
