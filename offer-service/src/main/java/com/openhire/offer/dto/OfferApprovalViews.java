package com.openhire.offer.dto;

import java.time.Instant;
import java.util.List;

/** Read models for the combined approvals inbox; offers remain the source of truth. */
public final class OfferApprovalViews {
    private OfferApprovalViews() {}

    public record Decision(Instant at) {}

    public record Summary(
            String id,
            String type,
            String title,
            String requestedBy,
            Instant createdAt,
            String status,
            String stepLabel,
            Decision viewerDecision) {}

    public record Step(
            String id,
            String role,
            String approver,
            String status,
            Instant at,
            String decidedBy,
            String decidedName,
            String comment) {}

    public record Term(String label, String value) {}

    public record Action(String offerId, String stepId, int version, boolean canDecide) {}

    public record Detail(
            String id,
            String type,
            String title,
            String requestedBy,
            Instant createdAt,
            String status,
            String stepLabel,
            Decision viewerDecision,
            List<Step> steps,
            List<Term> terms,
            Action offerAction) {}

    public record Counts(long waitingOnMe, long requestedByMe, long completed) {}
}
