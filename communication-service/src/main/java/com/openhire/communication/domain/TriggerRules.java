package com.openhire.communication.domain;

import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Observer rules: which lifecycle events message the candidate and which notify staff.
 * Rejection messages are deliberately not automatic; a recruiter sends those from a template.
 */
public final class TriggerRules {
    public record CandidateMessage(String templateKey, Map<String, String> variables, boolean transactional) {}

    public record StaffAlert(
            String recipient,
            String type,
            NotificationEventKey eventKey,
            String title,
            String body,
            String route,
            Map<String, String> params) {}

    public record Plan(CandidateMessage candidateMessage, List<StaffAlert> alerts) {
        public static Plan none() {
            return new Plan(null, List.of());
        }
    }

    private static final Set<String> HANDLED = Set.of(
            "ApplicationSubmitted",
            "interview.scheduled.v1",
            "interview.rescheduled.v1",
            "interview.cancelled.v1",
            "interview.scheduling-link.issued.v1",
            "interview.feedback-overdue.v1",
            "offer.approval.requested.v1",
            "offer.approval.step-approved.v1",
            "offer.approval.rejected.v1",
            "offer.approved.v1",
            "offer.sent.v1",
            "offer.viewed.v1",
            "offer.negotiation.requested.v1",
            "offer.accepted.v1",
            "offer.declined.v1",
            "offer.expired.v1",
            "employee.created.v1");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.ENGLISH);

    private TriggerRules() {}

    public static boolean handles(String eventType) {
        return HANDLED.contains(eventType);
    }

    public static Plan plan(String eventType, Map<String, Object> payload, String candidateName, String jobTitle) {
        var first = firstName(candidateName);
        var vars = new LinkedHashMap<String, String>();
        vars.put("candidate.firstName", first);
        vars.put("candidate.name", candidateName == null ? "" : candidateName);
        vars.put("job.title", text(payload, "jobTitle", jobTitle));
        vars.put("round.name", text(payload, "roundName", ""));
        vars.put("meeting.url", text(payload, "meetingUrl", ""));
        var zone = text(payload, "timezone", "UTC");
        vars.put("round.timezone", zone);
        vars.put("round.start", formatStart(text(payload, "start", ""), zone));
        vars.put("offer.url", text(payload, "offerUrl", ""));
        vars.put("offer.expiryDate", longDate(text(payload, "expiryDate", "")));
        vars.put("offer.joiningDate", longDate(joiningDate(payload)));
        var round = vars.get("round.name");
        var who = candidateName == null || candidateName.isBlank() ? "a candidate" : candidateName;
        var alerts = new ArrayList<StaffAlert>();
        return switch (eventType) {
            case "ApplicationSubmitted" ->
                new Plan(new CandidateMessage("application-received", vars, true), List.of());
            case "interview.scheduled.v1" -> {
                for (var email : panelEmails(payload))
                    alerts.add(new StaffAlert(
                            "email:" + email,
                            "ASSIGNED",
                            NotificationEventKey.ASSIGNED_TO_JOB_ROUND,
                            "You're interviewing " + who,
                            round + ", " + vars.get("round.start"),
                            "myInterviews",
                            Map.of()));
                owner(payload)
                        .ifPresent(owner -> alerts.add(new StaffAlert(
                                owner,
                                "INTERVIEW_SCHEDULED",
                                NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED,
                                round + " booked with " + who,
                                vars.get("round.start"),
                                "interviews",
                                Map.of())));
                yield new Plan(new CandidateMessage("interview-invitation", vars, true), alerts);
            }
            case "interview.rescheduled.v1" -> {
                for (var recipient : panelAndOwner(payload))
                    alerts.add(new StaffAlert(
                            recipient,
                            "INTERVIEW_SCHEDULED",
                            NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED,
                            round + " with " + who + " moved",
                            "Now " + vars.get("round.start"),
                            "interviews",
                            Map.of()));
                yield new Plan(new CandidateMessage("interview-rescheduled", vars, true), alerts);
            }
            case "interview.cancelled.v1" -> {
                boolean wasBooked = Boolean.TRUE.equals(payload.get("notifyCandidate"));
                if ("CANDIDATE".equals(payload.get("cancelledBy"))) {
                    for (var recipient : panelAndOwner(payload))
                        alerts.add(new StaffAlert(
                                recipient,
                                "INTERVIEW_SCHEDULED",
                                NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED,
                                who + " cancelled " + round,
                                text(payload, "reason", "Cancelled by the candidate"),
                                "interviews",
                                Map.of()));
                    yield new Plan(null, alerts);
                }
                if (wasBooked)
                    for (var recipient : panelAndOwner(payload))
                        alerts.add(new StaffAlert(
                                recipient,
                                "INTERVIEW_SCHEDULED",
                                NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED,
                                round + " with " + who + " cancelled",
                                "The booking was released",
                                "interviews",
                                Map.of()));
                yield new Plan(wasBooked ? new CandidateMessage("interview-cancelled", vars, true) : null, alerts);
            }
            case "offer.approval.requested.v1" -> {
                for (var assignee : strings(payload.get("currentApprovers")))
                    alerts.add(new StaffAlert(
                            "user:" + assignee,
                            "APPROVAL_REQUESTED",
                            NotificationEventKey.APPROVAL_REQUESTED,
                            "Offer approval needed",
                            text(payload, "title", "An offer needs your approval"),
                            "offers",
                            Map.of("offerId", text(payload, "offerId", ""))));
                yield new Plan(null, alerts);
            }
            case "offer.approved.v1", "offer.approval.step-approved.v1" -> {
                for (var recipient : ownerAndHr(payload))
                    alerts.add(offerAlert(
                            recipient,
                            "APPROVAL_DECIDED",
                            (eventType.equals("offer.approved.v1")
                                            ? "Offer approved: "
                                            : "Offer approval step completed: ")
                                    + who,
                            eventType.equals("offer.approved.v1")
                                    ? "Ready to send to the candidate."
                                    : "The offer is progressing through its assigned approval steps.",
                            payload,
                            NotificationEventKey.APPROVAL_REQUESTED));
                yield new Plan(null, alerts);
            }
            case "offer.approval.rejected.v1" -> {
                for (var recipient : ownerAndHr(payload))
                    alerts.add(offerAlert(
                            recipient,
                            "APPROVAL_DECIDED",
                            "Offer rejected: " + who,
                            text(payload, "reason", "An approver rejected the offer."),
                            payload,
                            NotificationEventKey.APPROVAL_REQUESTED));
                yield new Plan(null, alerts);
            }
            case "offer.sent.v1" -> new Plan(new CandidateMessage("offer-sent", vars, true), List.of());
            case "offer.viewed.v1" -> {
                owner(payload)
                        .ifPresent(owner -> alerts.add(offerAlert(
                                owner,
                                "OFFER_UPDATED",
                                who + " viewed the offer",
                                "They have opened it but not responded yet.",
                                payload,
                                NotificationEventKey.OFFER_ACCEPTED_DECLINED)));
                yield new Plan(null, alerts);
            }
            case "offer.negotiation.requested.v1" -> {
                owner(payload)
                        .ifPresent(owner -> alerts.add(offerAlert(
                                owner,
                                "OFFER_UPDATED",
                                who + " asked for changes",
                                text(payload, "message", ""),
                                payload,
                                NotificationEventKey.OFFER_ACCEPTED_DECLINED)));
                yield new Plan(null, alerts);
            }
            case "offer.accepted.v1" -> {
                for (var recipient : ownerAndHr(payload))
                    alerts.add(offerAlert(
                            recipient,
                            "OFFER_UPDATED",
                            who + " accepted the offer",
                            "Joining " + vars.get("offer.joiningDate") + ". The hire is being set up.",
                            payload,
                            NotificationEventKey.OFFER_ACCEPTED_DECLINED));
                yield new Plan(new CandidateMessage("offer-accepted", vars, true), alerts);
            }
            case "offer.declined.v1" -> {
                for (var recipient : ownerAndHr(payload))
                    alerts.add(offerAlert(
                            recipient,
                            "OFFER_UPDATED",
                            who + " declined the offer",
                            text(payload, "reason", "No reason given."),
                            payload,
                            NotificationEventKey.OFFER_ACCEPTED_DECLINED));
                yield new Plan(null, alerts);
            }
            case "offer.expired.v1" -> {
                for (var recipient : ownerAndHr(payload))
                    alerts.add(offerAlert(
                            recipient,
                            "OFFER_UPDATED",
                            "The offer to " + who + " expired",
                            "No response before the deadline.",
                            payload,
                            NotificationEventKey.OFFER_ACCEPTED_DECLINED));
                yield new Plan(null, alerts);
            }
            case "employee.created.v1" -> {
                alerts.add(new StaffAlert(
                        "role:HR_ADMIN",
                        "NEW_HIRE",
                        NotificationEventKey.OFFER_ACCEPTED_DECLINED,
                        "New hire: " + who + " (" + text(payload, "employeeNo", "") + ")",
                        "Joins " + vars.get("offer.joiningDate") + ". Onboarding has started.",
                        "employees",
                        Map.of()));
                yield new Plan(null, alerts);
            }
            case "interview.scheduling-link.issued.v1" -> {
                vars.put("booking.url", text(payload, "bookingUrl", ""));
                yield new Plan(new CandidateMessage("scheduling-link", vars, true), List.of());
            }
            case "interview.feedback-overdue.v1" -> {
                var missing = missing(payload);
                for (var email : missing)
                    alerts.add(new StaffAlert(
                            "email:" + email,
                            "SCORECARD_OVERDUE",
                            NotificationEventKey.SCORECARD_OVERDUE,
                            "Scorecard overdue for " + who,
                            round + " feedback is past due",
                            "myInterviews",
                            Map.of()));
                owner(payload)
                        .ifPresent(owner -> alerts.add(new StaffAlert(
                                owner,
                                "SCORECARD_OVERDUE",
                                NotificationEventKey.SCORECARD_OVERDUE,
                                "Feedback overdue for " + who,
                                round + " is still missing scorecards",
                                "interviews",
                                Map.of())));
                yield new Plan(null, alerts);
            }
            default -> Plan.none();
        };
    }

    static String formatStart(String iso, String zone) {
        if (iso == null || iso.isBlank()) return "";
        try {
            return WHEN.format(Instant.parse(iso).atZone(ZoneId.of(zone)));
        } catch (RuntimeException invalid) {
            return iso;
        }
    }

    /** Approval events: current approvers hear a decision is waiting; the requester hears the outcome. */
    public static List<StaffAlert> approvalAlerts(String eventType, Map<String, Object> payload) {
        var id = text(payload, "approvalId", "");
        var title = text(payload, "title", "an approval request");
        var status = text(payload, "status", "");
        var waiting = new ArrayList<StaffAlert>();
        if (status.equals("PENDING"))
            for (var recipient : approvers(payload))
                waiting.add(new StaffAlert(
                        recipient,
                        "APPROVAL_REQUESTED",
                        NotificationEventKey.APPROVAL_REQUESTED,
                        eventType.equals("approval.sla-reminder.v1") ? "Reminder: approval overdue" : "Approval needed",
                        title,
                        "approvals",
                        Map.of("id", id)));
        var requester = text(payload, "requestedBy", "");
        var outcome =
                switch (eventType) {
                    case "approval.approved.v1" -> status.equals("APPROVED") ? "Approved" : "Approval step completed";
                    case "approval.rejected.v1" -> "Rejected";
                    case "approval.changes-requested.v1" -> "Changes requested";
                    default -> null;
                };
        return switch (eventType) {
            case "approval.requested.v1", "approval.delegated.v1", "approval.sla-reminder.v1" -> waiting;
            case "approval.approved.v1", "approval.rejected.v1", "approval.changes-requested.v1" -> {
                var alerts = new ArrayList<>(waiting); // a multi-step approval moves on to the next approvers
                if (outcome != null && !requester.isBlank())
                    alerts.add(new StaffAlert(
                            "user:" + requester,
                            "APPROVAL_DECIDED",
                            NotificationEventKey.APPROVAL_REQUESTED,
                            outcome + ": " + title,
                            "Your approval request has a decision.",
                            "approvals",
                            Map.of("id", id, "scope", "requested_by_me")));
                if (outcome != null) {
                    var notified = new LinkedHashSet<>(
                            alerts.stream().map(StaffAlert::recipient).toList());
                    for (var participant : strings(payload.get("participants"))) {
                        var recipient = "user:" + participant;
                        if (!participant.isBlank() && !"me".equalsIgnoreCase(participant) && notified.add(recipient))
                            alerts.add(new StaffAlert(
                                    recipient,
                                    "APPROVAL_DECIDED",
                                    NotificationEventKey.APPROVAL_REQUESTED,
                                    outcome + ": " + title,
                                    "An approval request assigned to you has been updated.",
                                    "approvals",
                                    Map.of("id", id)));
                    }
                }
                yield alerts;
            }
            default -> List.of();
        };
    }

    private static List<String> approvers(Map<String, Object> payload) {
        var result = new LinkedHashSet<String>();
        if (payload.get("currentApprovers") instanceof List<?> list)
            for (var item : list) {
                var approver = item == null ? "" : item.toString();
                // Legacy unassigned requests must be assigned explicitly, never broadcast.
                if (!approver.isBlank() && !approver.equalsIgnoreCase("me")) result.add("user:" + approver);
            }
        return List.copyOf(result);
    }

    private static StaffAlert offerAlert(
            String recipient,
            String type,
            String title,
            String body,
            Map<String, Object> payload,
            NotificationEventKey key) {
        return new StaffAlert(
                recipient, type, key, title, body, "offers", Map.of("offerId", text(payload, "offerId", "")));
    }

    /** The recruiter who created the offer, plus HR, deduplicated by recipient key. */
    private static List<String> ownerAndHr(Map<String, Object> payload) {
        var result = new LinkedHashSet<String>();
        owner(payload).ifPresent(result::add);
        for (var id : strings(payload.get("approvalParticipants"))) result.add("user:" + id);
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list) for (var item : list) if (item != null) result.add(item.toString());
        return result;
    }

    private static String joiningDate(Map<String, Object> payload) {
        if (payload.get("terms") instanceof Map<?, ?> terms && terms.get("joiningDate") != null)
            return terms.get("joiningDate").toString();
        return text(payload, "joiningDate", "");
    }

    private static String longDate(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            return java.time.LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso)
                    .format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
        } catch (RuntimeException invalid) {
            return iso;
        }
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) return "there";
        return name.trim().split("\\s+")[0];
    }

    private static java.util.Optional<String> owner(Map<String, Object> payload) {
        var owner = payload.get("ownerId");
        return owner == null || owner.toString().isBlank() || "null".equals(owner.toString())
                ? java.util.Optional.empty()
                : java.util.Optional.of("user:" + owner);
    }

    private static List<String> panelAndOwner(Map<String, Object> payload) {
        var result = new LinkedHashSet<String>();
        panelEmails(payload).forEach(email -> result.add("email:" + email));
        owner(payload).ifPresent(result::add);
        return List.copyOf(result);
    }

    private static List<String> panelEmails(Map<String, Object> payload) {
        return emails(payload.get("panel"));
    }

    private static List<String> missing(Map<String, Object> payload) {
        return emails(payload.get("missingInterviewers"));
    }

    private static List<String> emails(Object value) {
        var result = new ArrayList<String>();
        if (value instanceof List<?> list)
            for (var item : list)
                if (item instanceof Map<?, ?> member && member.get("email") != null)
                    result.add(member.get("email").toString().toLowerCase(Locale.ROOT));
        return result;
    }

    private static String text(Map<String, Object> payload, String key, String fallback) {
        var value = payload.get(key);
        return value == null ? (fallback == null ? "" : fallback) : value.toString();
    }
}
