package com.openhire.offer.service;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.StepStatus;
import com.openhire.offer.domain.OfferTypes.Viewer;
import com.openhire.offer.dto.OfferApprovalViews.*;
import com.openhire.offer.dto.OfferViews.Page;
import com.openhire.offer.service.port.OfferPorts.OfferRepository;
import com.openhire.platform.web.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class OfferApprovalInbox {
    private final OfferRepository offers;
    private final OfferApplicationService workflows;

    public OfferApprovalInbox(OfferRepository offers, OfferApplicationService workflows) {
        this.offers = offers;
        this.workflows = workflows;
    }

    public Page<Summary> list(String tenant, Viewer viewer, String scope) {
        requireRead(viewer);
        var rows = offers.approvalInbox(tenant, viewer, scope, 201);
        return new Page<>(rows.stream().limit(200).map(o -> summary(o, viewer)).toList(), null, rows.size() > 200);
    }

    public Counts counts(String tenant, Viewer viewer) {
        requireRead(viewer);
        return new Counts(
                offers.countApprovalInbox(tenant, viewer, "waiting_on_me"),
                offers.countApprovalInbox(tenant, viewer, "requested_by_me"),
                offers.countApprovalInbox(tenant, viewer, "completed"));
    }

    public Detail get(String tenant, Viewer viewer, String id) {
        requireRead(viewer);
        var o = require(tenant, id);
        var s = summary(o, viewer);
        var t = o.latest().terms();
        var band = o.band();
        var base = money(t.currency(), t.base())
                + (band == null ? "" : " (band " + number(band.min()) + " to " + number(band.max()) + ")");
        var delta = band == null
                ? "Not available"
                : money(t.currency(), t.base().subtract(band.midpoint()).max(BigDecimal.ZERO));
        var step = o.currentStep();
        return new Detail(
                s.id(),
                s.type(),
                s.title(),
                s.requestedBy(),
                s.createdAt(),
                s.status(),
                s.stepLabel(),
                s.viewerDecision(),
                o.approvalSteps().stream()
                        .map(p -> new Step(
                                p.id(),
                                o.approvalAssignees().getOrDefault(p.role(), "Not assigned"),
                                p.label(),
                                p.status().name(),
                                p.decidedAt(),
                                p.decidedBy(),
                                p.decidedName(),
                                p.comment()))
                        .toList(),
                List.of(
                        new Term("Base salary", base),
                        new Term("Above midpoint by", delta),
                        new Term("Joining bonus", money(t.currency(), t.bonus())),
                        new Term(
                                "Joining date",
                                java.time.LocalDate.parse(t.joiningDate())
                                        .format(java.time.format.DateTimeFormatter.ofPattern(
                                                "MMM d, uuuu", Locale.ENGLISH)))),
                new Action(o.id(), step == null ? null : step.id(), o.latest().versionNo(), canDecide(o, viewer)));
    }

    public void decide(
            String tenant, Viewer viewer, String id, String stepId, int version, String reason, boolean approve) {
        requireRead(viewer);
        var o = require(tenant, id);
        if (!canDecide(o, viewer))
            throw new OfferApplicationService.AccessDenied("This offer is not awaiting your approval");
        if (o.latest().versionNo() != version
                || o.currentStep() == null
                || !o.currentStep().id().equals(stepId))
            throw new IllegalStateException("The offer approval changed; reload before deciding");
        // Delegate to the existing aggregate: role, self-approval and distinct-approver checks stay intact.
        if (approve) workflows.approve(tenant, viewer, id, stepId, version);
        else workflows.reject(tenant, viewer, id, stepId, reason, version);
    }

    static Summary summary(Offer o, Viewer viewer) {
        var current = o.currentStep();
        var own = o.approvalSteps().stream()
                .filter(p -> p.status() == StepStatus.DONE && viewer.subject().equals(p.decidedBy()))
                .findFirst()
                .map(p -> new Decision(p.decidedAt()))
                .orElse(null);
        return new Summary(
                "offer:" + o.id(),
                "OFFER",
                o.candidateName() + ", " + o.jobTitle(),
                o.ownerId(),
                o.createdAt(),
                o.status() == Status.PENDING_APPROVAL
                        ? "PENDING"
                        : o.status() == Status.DRAFT ? "CHANGES_REQUESTED" : "APPROVED",
                o.status() == Status.DRAFT
                        ? "Returned to draft"
                        : current == null ? "Approval complete" : current.label() + " step",
                own);
    }

    static boolean canDecide(Offer o, Viewer viewer) {
        return o.status() == Status.PENDING_APPROVAL
                && o.currentStep() != null
                && o.assigned(o.currentStep(), viewer)
                && !viewer.subject().equals(o.ownerId())
                && o.approvalSteps().stream().noneMatch(p -> viewer.subject().equals(p.decidedBy()));
    }

    private Offer require(String tenant, String id) {
        return offers.find(tenant, id)
                .filter(o -> !o.approvalSteps().isEmpty()
                        || (o.approvalNote() != null && !o.approvalNote().isBlank()))
                .orElseThrow(() -> new ResourceNotFoundException("Offer approval was not found"));
    }

    private static void requireRead(Viewer viewer) {
        if (!viewer.canRead()) throw new OfferApplicationService.AccessDenied("Recruiting access required");
    }

    private static String money(String currency, BigDecimal value) {
        return currency + " " + number(value);
    }

    private static String number(BigDecimal value) {
        // Indian-locale grouping, without converting the salary-band unit or rounding values.
        var parts = value.stripTrailingZeros().toPlainString().split("\\.", 2);
        var integer = parts[0];
        if (integer.length() > 3)
            integer = integer.substring(0, integer.length() - 3).replaceAll("(\\d)(?=(\\d{2})+$)", "$1,") + ","
                    + integer.substring(integer.length() - 3);
        return integer + (parts.length == 2 ? "." + parts[1] : "");
    }
}
