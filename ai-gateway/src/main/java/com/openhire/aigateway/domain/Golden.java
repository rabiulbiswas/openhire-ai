package com.openhire.aigateway.domain;

import com.openhire.aigateway.domain.OutputGuard.Chunk;
import java.util.List;

/** The versioned golden dataset used to evaluate prompt versions. */
public final class Golden {
    private Golden() {}

    public record Criterion(String id, String label, String requirement, String description) {}

    /**
     * One hand-written case. {@code pairId} links counterfactual twins that differ only in a protected signal;
     * {@code trap} names a deliberate trap (KEYWORD_STUFFING, INJECTION, NO_EVIDENCE) or is null.
     */
    public record Case(
            String id,
            String pairId,
            String trap,
            String candidateName,
            Criterion criterion,
            List<Chunk> chunks,
            int min,
            int max) {}

    public record Dataset(String version, List<Case> cases) {}
}
