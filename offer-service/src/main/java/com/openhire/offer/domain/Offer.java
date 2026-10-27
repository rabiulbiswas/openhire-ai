package com.openhire.offer.domain;

import com.openhire.offer.domain.OfferTypes.ApprovalStep;
import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.domain.OfferTypes.OfferVersion;
import com.openhire.offer.domain.OfferTypes.PendingEvent;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.StepStatus;
import com.openhire.offer.domain.OfferTypes.Terms;
import com.openhire.offer.domain.OfferTypes.Viewer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Aggregate root: one offer per application, with immutable versions and its approval chain. */
@Document("offers")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_created_page", def = "{'tenantId': 1, 'createdAt': -1, '_id': -1}"),
    @CompoundIndex(
            name = "tenant_status_created_page",
            def = "{'tenantId': 1, 'status': 1, 'createdAt': -1, '_id': -1}"),
    @CompoundIndex(name = "tenant_application", def = "{'tenantId': 1, 'applicationId': 1}", unique = true),
    @CompoundIndex(name = "tenant_status", def = "{'tenantId': 1, 'status': 1, 'updatedAt': -1}"),
    @CompoundIndex(name = "tenant_owner_updated", def = "{'tenantId': 1, 'ownerId': 1, 'updatedAt': -1}"),
    @CompoundIndex(name = "expiry_sweep", def = "{'status': 1, 'expiresAt': 1}")
})
public class Offer {
    public static final String PRODUCER = "offer-service";

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String applicationId;
    private String candidateId;
    private String candidateName;
    private String jobId;
    private String jobTitle;
    private String ownerId;
    private Status status;
    private Band band;
    private List<OfferVersion> versions;
    private List<ApprovalStep> approvalSteps;
    private Map<String, String> approvalAssignees = new LinkedHashMap<>();

    public Map<String, String> approvalAssignees() {
        return approvalAssignees == null ? Map.of() : Map.copyOf(approvalAssignees);
    }

    public void assignApprovers(Map<String, String> assignees) {
        if (status != Status.DRAFT
                && !(status == Status.PENDING_APPROVAL && approvalAssignees().isEmpty()))
            throw new IllegalStateException("Approvers are frozen once approval starts");
        for (var step : approvalSteps)
            if (step.status() == StepStatus.DONE
                    && !java.util.Objects.equals(step.decidedBy(), assignees.get(step.role())))
                throw new IllegalArgumentException("Completed approval assignments cannot be changed");
        approvalAssignees = new LinkedHashMap<>(assignees);
        if (status == Status.PENDING_APPROVAL) requestCurrentStep(Instant.now());
    }

    public boolean assigned(ApprovalStep step, Viewer viewer) {
        return viewer.subject().equals(approvalAssignees().get(step.role())) && viewer.canApprove(step.role());
    }

    private String approvalNote;
    private Instant expiresAt;
    private Instant sentAt;
    private Instant viewedAt;
    private Instant respondedAt;
    private String acceptedBy;
    private String candidateNote;
    private String responseReason;
    private Instant createdAt;
    private Instant updatedAt;

    @Field("_events")
    private List<PendingEvent> events = new ArrayList<>();

    protected Offer() {}

    public static String idFor(String tenantId, String applicationId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000" + applicationId).getBytes(StandardCharsets.UTF_8));
            return "offer_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Offer create(
            String tenantId,
            String applicationId,
            String candidateId,
            String candidateName,
            String jobId,
            String jobTitle,
            String ownerId,
            Band band,
            OfferVersion first,
            Instant now) {
        var offer = new Offer();
        offer.id = idFor(tenantId, applicationId);
        offer.tenantId = tenantId;
        offer.applicationId = applicationId;
        offer.candidateId = candidateId;
        offer.candidateName = candidateName;
        offer.jobId = jobId;
        offer.jobTitle = jobTitle;
        offer.ownerId = ownerId;
        offer.status = Status.DRAFT;
        offer.band = band;
        offer.versions = new ArrayList<>(List.of(first));
        offer.approvalSteps = new ArrayList<>();
        offer.createdAt = now;
        offer.updatedAt = now;
        offer.record("offer.created.v1", Map.of(), now);
        return offer;
    }

    /** A closed (declined, expired or rescinded) offer is started again as a new draft. */
    public void reopen(String newOwnerId, Band newBand, OfferVersion next, Instant now) {
        if (!status.closed()) throw new IllegalStateException("This application already has an offer in progress");
        status = Status.DRAFT;
        ownerId = newOwnerId;
        band = newBand;
        versions.add(next);
        approvalSteps = new ArrayList<>();
        approvalNote = null;
        expiresAt = null;
        sentAt = null;
        viewedAt = null;
        respondedAt = null;
        acceptedBy = null;
        candidateNote = null;
        responseReason = null;
        record("offer.created.v1", Map.of(), now);
    }

    /** Saves new terms as a version. Compensation changes after submission restart the approval chain. */
    public boolean revise(OfferVersion draft, Band currentBand, Instant now) {
        if (status == Status.ACCEPTED) throw new IllegalStateException("An accepted offer cannot be changed");
        if (status.closed()) throw new IllegalStateException("This offer is " + status + "; start a new one instead");
        var current = latest();
        if (current.terms().equals(draft.terms()) && java.util.Objects.equals(current.coverNote(), draft.coverNote()))
            return false;
        band = currentBand;
        boolean compensation = CompensationPolicy.reapprovalRequired(current.terms(), draft.terms());
        boolean submitted = status != Status.DRAFT;
        var version = new OfferVersion(
                current.frozen() ? current.versionNo() + 1 : current.versionNo(),
                draft.templateKey(),
                draft.templateVersion(),
                draft.terms(),
                draft.coverNote(),
                draft.letterText(),
                draft.sha256(),
                draft.createdBy(),
                now,
                submitted,
                false);
        if (current.frozen()) versions.add(version);
        else versions.set(versions.size() - 1, version);
        var wasSent = status.open();
        boolean reapproval = compensation && submitted;
        if (reapproval) {
            if (CompensationPolicy.chain(latest().terms(), band).stream()
                    .allMatch(s -> approvalAssignees().containsKey(s.role()))) startApproval(now);
            else {
                status = Status.DRAFT;
                approvalSteps = new ArrayList<>();
                approvalNote = "Select the required named approvers and submit the revised terms";
            }
        } else if (wasSent) status = Status.APPROVED; // the candidate must be sent the revised terms
        if (submitted)
            record("offer.revised.v1", Map.of("reapprovalRequired", reapproval, "versionNo", version.versionNo()), now);
        updatedAt = now;
        return true;
    }

    public void submit(Instant now) {
        if (status != Status.DRAFT) throw new IllegalStateException("Only a draft can be submitted for approval");
        versions.set(versions.size() - 1, latest().freeze());
        startApproval(now);
        updatedAt = now;
    }

    private void startApproval(Instant now) {
        var terms = latest().terms();
        approvalSteps = new ArrayList<>(CompensationPolicy.chain(terms, band));
        approvalNote = CompensationPolicy.chainNote(terms, band);
        status = Status.PENDING_APPROVAL;
        requestCurrentStep(now);
    }

    private void requestCurrentStep(Instant now) {
        var step = currentStep();
        if (step == null) return;
        var extra = new LinkedHashMap<String, Object>();
        extra.put("stepId", step.id());
        var assignee = approvalAssignees().get(step.role());
        extra.put("currentApprovers", assignee == null ? List.of() : List.of(assignee));
        extra.put("title", jobTitle + ": " + candidateName);
        record("offer.approval.requested.v1", extra, now);
    }

    /** Returns false when the same person already approved this step (idempotent retry). */
    public boolean approve(String stepId, Viewer viewer, Instant now) {
        requirePending();
        var step = step(stepId);
        if (step.status() == StepStatus.DONE && viewer.subject().equals(step.decidedBy())) return false;
        if (step.status() != StepStatus.CURRENT) throw new IllegalStateException("This step is not awaiting approval");
        authorise(step, viewer);
        var index = approvalSteps.indexOf(step);
        approvalSteps.set(index, step.decided(viewer.subject(), viewer.displayName(), now, null));
        var next = approvalSteps.stream()
                .filter(candidate -> candidate.status() == StepStatus.PENDING)
                .findFirst();
        if (next.isPresent()) {
            approvalSteps.set(approvalSteps.indexOf(next.get()), next.get().with(StepStatus.CURRENT));
            record("offer.approval.step-approved.v1", Map.of("stepId", step.id(), "approvedBy", viewer.subject()), now);
            requestCurrentStep(now);
        } else {
            status = Status.APPROVED;
            record("offer.approved.v1", Map.of(), now);
        }
        updatedAt = now;
        return true;
    }

    public void reject(String stepId, Viewer viewer, String reason, Instant now) {
        requirePending();
        if (reason == null || reason.isBlank())
            throw new IllegalArgumentException("Say why you are rejecting the offer");
        var step = step(stepId);
        if (step.status() != StepStatus.CURRENT) throw new IllegalStateException("This step is not awaiting approval");
        authorise(step, viewer);
        status = Status.DRAFT;
        approvalSteps = new ArrayList<>();
        approvalNote = step.label() + " rejected the offer: " + reason.trim();
        record("offer.approval.rejected.v1", Map.of("reason", reason.trim(), "stepRole", step.role()), now);
        updatedAt = now;
    }

    private void authorise(ApprovalStep step, Viewer viewer) {
        if (viewer.subject().equals(ownerId))
            throw new IllegalStateException("You cannot approve an offer you created");
        if (approvalSteps.stream().anyMatch(other -> viewer.subject().equals(other.decidedBy())))
            throw new IllegalStateException("A different person must approve each step");
        if (!assigned(step, viewer))
            throw new IllegalArgumentException("Only the assigned " + step.label() + " can approve this step");
    }

    /** Returns false when the offer was already sent (idempotent retry). */
    public boolean send(Instant expiry, String offerUrl, Instant now) {
        if (status.open()) return false;
        if (status != Status.APPROVED) throw new IllegalStateException("Only an approved offer can be sent");
        versions.set(versions.size() - 1, latest().markSent());
        status = Status.SENT;
        sentAt = now;
        expiresAt = expiry;
        viewedAt = null;
        respondedAt = null;
        candidateNote = null;
        var extra = new LinkedHashMap<String, Object>();
        extra.put("offerUrl", offerUrl);
        extra.put("expiresAt", expiry.toString());
        extra.put("expiryDate", latest().terms().expiryDate());
        extra.put("joiningDate", latest().terms().joiningDate());
        extra.put("designation", latest().terms().designation());
        record("offer.sent.v1", extra, now);
        updatedAt = now;
        return true;
    }

    public void markViewed(Instant now) {
        if (status != Status.SENT) return;
        status = Status.VIEWED;
        viewedAt = now;
        record("offer.viewed.v1", Map.of(), now);
        updatedAt = now;
    }

    public void negotiate(String message, Instant now) {
        requireRespondable(now);
        if (message == null || message.isBlank())
            throw new IllegalArgumentException("Tell us what you would like changed");
        if (message.length() > 2000) throw new IllegalArgumentException("The message is too long");
        status = Status.NEGOTIATING;
        candidateNote = message.trim();
        record("offer.negotiation.requested.v1", Map.of("message", candidateNote), now);
        updatedAt = now;
    }

    /** Returns false when the offer was already accepted (idempotent retry). */
    public boolean accept(String fullName, Instant now) {
        if (status == Status.ACCEPTED) return false;
        requireRespondable(now);
        if (!normalise(fullName).equals(normalise(candidateName)))
            throw new IllegalArgumentException("Type your full name exactly as it appears above to accept");
        status = Status.ACCEPTED;
        respondedAt = now;
        acceptedBy = fullName.trim();
        var terms = sentVersion().terms();
        var extra = new LinkedHashMap<String, Object>();
        extra.put("versionNo", sentVersion().versionNo());
        extra.put("terms", termsPayload(terms));
        extra.put("acceptedAt", now.toString());
        extra.put("acceptedBy", acceptedBy);
        record("offer.accepted.v1", extra, now);
        updatedAt = now;
        return true;
    }

    /** Returns false when the offer was already declined (idempotent retry). */
    public boolean decline(String reason, Instant now) {
        if (status == Status.DECLINED) return false;
        requireRespondable(now);
        status = Status.DECLINED;
        respondedAt = now;
        responseReason = reason == null || reason.isBlank() ? null : reason.trim();
        record("offer.declined.v1", responseReason == null ? Map.of() : Map.of("reason", responseReason), now);
        updatedAt = now;
        return true;
    }

    public boolean expire(Instant now) {
        if (!status.open() || expiresAt == null || expiresAt.isAfter(now)) return false;
        status = Status.EXPIRED;
        record("offer.expired.v1", Map.of(), now);
        updatedAt = now;
        return true;
    }

    public void rescind(String reason, Instant now) {
        if (status == Status.ACCEPTED) throw new IllegalStateException("An accepted offer cannot be rescinded here");
        if (status == Status.RESCINDED) return;
        if (status.closed()) throw new IllegalStateException("This offer is already " + status);
        status = Status.RESCINDED;
        responseReason = reason == null || reason.isBlank() ? null : reason.trim();
        respondedAt = now;
        record("offer.rescinded.v1", responseReason == null ? Map.of() : Map.of("reason", responseReason), now);
        updatedAt = now;
    }

    // ---- Queries ---------------------------------------------------------------------------------

    public OfferVersion latest() {
        return versions.getLast();
    }

    /** The version the candidate was shown, or null when nothing has been sent. */
    public OfferVersion sentVersion() {
        for (int i = versions.size() - 1; i >= 0; i--) if (versions.get(i).sent()) return versions.get(i);
        return null;
    }

    public boolean canRespond(Instant now) {
        return status.open() && expiresAt != null && expiresAt.isAfter(now);
    }

    /** Staff are revising an offer that the candidate has already been sent. */
    public boolean revising() {
        return sentVersion() != null
                && (status == Status.DRAFT || status == Status.PENDING_APPROVAL || status == Status.APPROVED);
    }

    public ApprovalStep currentStep() {
        return approvalSteps.stream()
                .filter(step -> step.status() == StepStatus.CURRENT)
                .findFirst()
                .orElse(null);
    }

    public static Map<String, Object> termsPayload(Terms terms) {
        var map = new LinkedHashMap<String, Object>();
        map.put("base", terms.base());
        map.put("currency", terms.currency());
        map.put("variable", terms.variable());
        map.put("bonus", terms.bonus());
        map.put("joiningDate", terms.joiningDate());
        map.put("location", terms.location());
        map.put("designation", terms.designation());
        map.put("reportsTo", terms.reportsTo());
        return map;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String candidateId() {
        return candidateId;
    }

    public String candidateName() {
        return candidateName;
    }

    public String jobId() {
        return jobId;
    }

    public String jobTitle() {
        return jobTitle;
    }

    public String ownerId() {
        return ownerId;
    }

    public Status status() {
        return status;
    }

    public Band band() {
        return band;
    }

    public List<OfferVersion> versions() {
        return List.copyOf(versions);
    }

    public List<ApprovalStep> approvalSteps() {
        return List.copyOf(approvalSteps);
    }

    public String approvalNote() {
        return approvalNote;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public Instant respondedAt() {
        return respondedAt;
    }

    public String candidateNote() {
        return candidateNote;
    }

    public String responseReason() {
        return responseReason;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    // ---- Internals -------------------------------------------------------------------------------

    private void requirePending() {
        if (status != Status.PENDING_APPROVAL) throw new IllegalStateException("This offer is not awaiting approval");
    }

    private void requireRespondable(Instant now) {
        if (!status.open()) throw new IllegalStateException("This offer is no longer open for a response");
        if (!canRespond(now)) throw new IllegalStateException("This offer has expired");
    }

    private ApprovalStep step(String stepId) {
        return approvalSteps.stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown approval step"));
    }

    private static String normalise(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void record(String type, Map<String, Object> extra, Instant now) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("offerId", id);
        payload.put("applicationId", applicationId);
        payload.put("candidateId", candidateId);
        payload.put("jobId", jobId);
        payload.put("jobTitle", jobTitle);
        payload.put("candidateName", candidateName);
        payload.put("versionNo", versions.getLast().versionNo());
        payload.put("status", status.name());
        if (ownerId != null) payload.put("ownerId", ownerId);
        payload.put(
                "approvalParticipants",
                approvalSteps.stream()
                        .map(s -> approvalAssignees().get(s.role()))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList());
        payload.putAll(extra);
        events.add(new PendingEvent(
                UUID.randomUUID().toString(),
                PRODUCER,
                type,
                1,
                now.toString(),
                tenantId,
                "Offer",
                id,
                applicationId,
                payload));
    }
}
