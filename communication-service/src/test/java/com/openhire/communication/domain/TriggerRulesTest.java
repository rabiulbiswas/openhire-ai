package com.openhire.communication.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhire.communication.domain.CommunicationTypes.NotificationEventKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TriggerRulesTest {
    private static Map<String, Object> scheduled() {
        return Map.of(
                "applicationId", "app-1",
                "roundName", "Technical",
                "start", "2026-10-13T05:30:00Z",
                "timezone", "Asia/Kolkata",
                "ownerId", "sub-recruiter",
                "panel", List.of(Map.of("name", "Asha", "email", "Asha@X.test"), Map.of("name", "Plan only")));
    }

    @Test
    void anApplicationReceivesAnAcknowledgementAndNoStaffAlert() {
        var plan = TriggerRules.plan("ApplicationSubmitted", Map.of("jobId", "j"), "Priya Das", "Backend Engineer");

        assertThat(plan.candidateMessage().templateKey()).isEqualTo("application-received");
        assertThat(plan.candidateMessage().variables())
                .containsEntry("candidate.firstName", "Priya")
                .containsEntry("job.title", "Backend Engineer");
        assertThat(plan.alerts()).isEmpty();
    }

    @Test
    void aBookedRoundInvitesTheCandidateAndAlertsPanelAndOwner() {
        var plan = TriggerRules.plan("interview.scheduled.v1", scheduled(), "Priya Das", "Backend Engineer");

        assertThat(plan.candidateMessage().templateKey()).isEqualTo("interview-invitation");
        assertThat(plan.candidateMessage().variables().get("round.start")).isEqualTo("Tue 13 Oct 2026, 11:00");
        assertThat(plan.alerts())
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("email:asha@x.test", "user:sub-recruiter");
        assertThat(plan.alerts().getFirst().eventKey()).isEqualTo(NotificationEventKey.ASSIGNED_TO_JOB_ROUND);
        assertThat(plan.alerts().getLast().eventKey()).isEqualTo(NotificationEventKey.INTERVIEW_SCHEDULED_CHANGED);
    }

    @Test
    void cancellingAnUnbookedRoundTellsNobody() {
        var payload = new java.util.HashMap<>(scheduled());
        payload.put("notifyCandidate", false);

        var plan = TriggerRules.plan("interview.cancelled.v1", payload, "Priya Das", "Backend Engineer");

        assertThat(plan.candidateMessage()).isNull();
        assertThat(plan.alerts()).isEmpty();
    }

    @Test
    void overdueFeedbackAlertsOnlyTheMissingInterviewersAndTheOwner() {
        var payload = Map.<String, Object>of(
                "roundName", "Technical",
                "ownerId", "sub-recruiter",
                "missingInterviewers", List.of(Map.of("name", "Ben", "email", "ben@x.test")));

        var plan = TriggerRules.plan("interview.feedback-overdue.v1", payload, "Priya Das", "Backend Engineer");

        assertThat(plan.candidateMessage()).isNull();
        assertThat(plan.alerts())
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("email:ben@x.test", "user:sub-recruiter");
        assertThat(plan.alerts()).allMatch(alert -> alert.eventKey() == NotificationEventKey.SCORECARD_OVERDUE);
    }

    @Test
    void rejectionsAreNotAutomatic() {
        assertThat(TriggerRules.handles("ApplicationStageChanged")).isFalse();
    }

    private static Map<String, Object> approval(String status, List<String> approvers) {
        return Map.of(
                "approvalId", "apr-1",
                "title", "Senior Backend Engineer",
                "status", status,
                "requestedBy", "sub-recruiter",
                "currentApprovers", approvers);
    }

    @Test
    void aNewApprovalAlertsAssignedApproversAndTheRoleForUnassignedSteps() {
        var alerts = TriggerRules.approvalAlerts("approval.requested.v1", approval("PENDING", List.of("sub-hm", "me")));

        assertThat(alerts)
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("user:sub-hm", "role:HIRING_MANAGER");
        assertThat(alerts.getFirst().type()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(alerts.getFirst().params()).containsEntry("id", "apr-1");
    }

    @Test
    void aFinalApprovalTellsTheRequesterAndAnIntermediateOneMovesToTheNextApprover() {
        var done = TriggerRules.approvalAlerts("approval.approved.v1", approval("APPROVED", List.of()));
        assertThat(done).singleElement().satisfies(alert -> {
            assertThat(alert.recipient()).isEqualTo("user:sub-recruiter");
            assertThat(alert.type()).isEqualTo("APPROVAL_DECIDED");
            assertThat(alert.title()).isEqualTo("Approved: Senior Backend Engineer");
        });

        var next = TriggerRules.approvalAlerts("approval.approved.v1", approval("PENDING", List.of("sub-finance")));
        assertThat(next).extracting(TriggerRules.StaffAlert::recipient).containsExactly("user:sub-finance");
    }

    @Test
    void rejectionAndChangeRequestsGoBackToTheRequester() {
        assertThat(TriggerRules.approvalAlerts("approval.rejected.v1", approval("REJECTED", List.of())))
                .extracting(TriggerRules.StaffAlert::title)
                .containsExactly("Rejected: Senior Backend Engineer");
        assertThat(TriggerRules.approvalAlerts(
                        "approval.changes-requested.v1", approval("CHANGES_REQUESTED", List.of())))
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("user:sub-recruiter");
        assertThat(TriggerRules.approvalAlerts("approval.decision-undone.v1", approval("PENDING", List.of("me"))))
                .isEmpty();
    }

    @Test
    void aCandidateCancellationAlertsTheTeamWithTheReasonAndSendsTheCandidateNothing() {
        var payload = new java.util.HashMap<>(scheduled());
        payload.put("notifyCandidate", false);
        payload.put("cancelledBy", "CANDIDATE");
        payload.put("reason", "Cancelled by the candidate: Accepted another offer");

        var plan = TriggerRules.plan("interview.cancelled.v1", payload, "Priya Das", "Backend Engineer");

        assertThat(plan.candidateMessage()).isNull();
        assertThat(plan.alerts())
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("email:asha@x.test", "user:sub-recruiter");
        assertThat(plan.alerts().getFirst().title()).startsWith("Priya Das cancelled");
        assertThat(plan.alerts().getFirst().body()).contains("Accepted another offer");
    }

    private static Map<String, Object> offer(Map<String, Object> extra) {
        var payload = new java.util.HashMap<String, Object>(Map.of(
                "offerId", "offer-1",
                "applicationId", "app-1",
                "jobTitle", "Backend Engineer",
                "ownerId", "sub-recruiter"));
        payload.putAll(extra);
        return payload;
    }

    @Test
    void aSentOfferEmailsTheCandidateTheLinkAndDeadline() {
        var plan = TriggerRules.plan(
                "offer.sent.v1",
                offer(Map.of("offerUrl", "https://portal/offer#token=abc", "expiryDate", "2026-10-22")),
                "Priya Das",
                "Backend Engineer");

        assertThat(plan.candidateMessage().templateKey()).isEqualTo("offer-sent");
        assertThat(plan.candidateMessage().transactional()).isTrue();
        assertThat(plan.candidateMessage().variables())
                .containsEntry("offer.url", "https://portal/offer#token=abc")
                .containsEntry("offer.expiryDate", "22 October 2026");
        assertThat(plan.alerts()).isEmpty();
    }

    @Test
    void offerApprovalsAlertTheApproverRolesAndFinanceIsTakenByOrgAdmins() {
        var alerts = TriggerRules.plan(
                        "offer.approval.requested.v1",
                        offer(Map.of(
                                "currentApprovers",
                                List.of("HR_ADMIN", "FINANCE"),
                                "title",
                                "Backend Engineer: Priya Das")),
                        "Priya Das",
                        "Backend Engineer")
                .alerts();

        assertThat(alerts)
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("role:HR_ADMIN", "role:ORG_ADMIN");
        assertThat(alerts.getFirst().type()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(alerts.getFirst().route()).isEqualTo("offers");
        assertThat(alerts.getFirst().params()).containsEntry("offerId", "offer-1");
    }

    @Test
    void anAcceptedOfferThanksTheCandidateAndTellsTheRecruiterAndHr() {
        var plan = TriggerRules.plan(
                "offer.accepted.v1",
                offer(Map.of("terms", Map.of("joiningDate", "2026-11-16"))),
                "Priya Das",
                "Backend Engineer");

        assertThat(plan.candidateMessage().templateKey()).isEqualTo("offer-accepted");
        assertThat(plan.candidateMessage().variables()).containsEntry("offer.joiningDate", "16 November 2026");
        assertThat(plan.alerts())
                .extracting(TriggerRules.StaffAlert::recipient)
                .containsExactly("user:sub-recruiter", "role:HR_ADMIN");
        assertThat(plan.alerts().getFirst().title()).isEqualTo("Priya Das accepted the offer");
    }

    @Test
    void declinedNegotiatingViewedAndRejectedOffersOnlyAlertStaff() {
        var declined =
                TriggerRules.plan("offer.declined.v1", offer(Map.of("reason", "Took another job")), "Priya Das", "Eng");
        assertThat(declined.candidateMessage()).isNull();
        assertThat(declined.alerts()).hasSize(2);
        assertThat(declined.alerts().getFirst().body()).isEqualTo("Took another job");

        var asked = TriggerRules.plan(
                "offer.negotiation.requested.v1", offer(Map.of("message", "Start 30 Nov?")), "Priya Das", "Eng");
        assertThat(asked.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.title()).isEqualTo("Priya Das asked for changes");
            assertThat(alert.body()).isEqualTo("Start 30 Nov?");
        });

        assertThat(TriggerRules.plan("offer.viewed.v1", offer(Map.of()), "Priya Das", "Eng")
                        .alerts())
                .hasSize(1);
        assertThat(TriggerRules.plan(
                                "offer.approval.rejected.v1", offer(Map.of("reason", "Too high")), "Priya Das", "Eng")
                        .alerts())
                .singleElement()
                .satisfies(alert -> assertThat(alert.type()).isEqualTo("APPROVAL_DECIDED"));
    }

    @Test
    void aNewEmployeeAlertsHrWithoutAnyPersonalDataBeyondTheName() {
        var plan = TriggerRules.plan(
                "employee.created.v1",
                Map.of("applicationId", "app-1", "employeeNo", "EMP-2026-00001", "joiningDate", "2026-11-16"),
                "Priya Das",
                "Eng");

        assertThat(plan.candidateMessage()).isNull();
        assertThat(plan.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.recipient()).isEqualTo("role:HR_ADMIN");
            assertThat(alert.title()).isEqualTo("New hire: Priya Das (EMP-2026-00001)");
            assertThat(alert.type()).isEqualTo("NEW_HIRE");
            assertThat(alert.route()).isEqualTo("employees");
        });
    }
}
