package com.openhire.talent.service;

import static com.openhire.talent.service.Memory.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.platform.web.ResourceNotFoundException;
import com.openhire.talent.AuditProbe;
import com.openhire.talent.service.InvitationService.AccessDenied;
import com.openhire.talent.service.InvitationService.Viewer;
import com.openhire.talent.service.port.TalentPorts.JobInfo;
import com.openhire.talent.service.port.TalentPorts.ReceiptRejected;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationConsentTest {
    private final Memory memory = new Memory();
    private final ProfileProjector projector = memory.projector();
    private final InvitationService invitations = memory.invitationService();
    private final Viewer recruiter = new Viewer("recruiter-1", Set.of("RECRUITER"));

    @BeforeEach
    void pool() {
        memory.pooled(
                projector,
                "cand-1",
                "Priya Das",
                "job-0",
                "Backend Engineer",
                "Java engineer with 5 years of experience.");
        memory.jobRows.put("job-new", new JobInfo("job-new", "Staff Engineer", "OPEN", "d", List.of(), List.of()));
    }

    @Test
    void anInviteSendsTheReengagementTemplateRecordsTheInvitationAndEmitsAnEvent() {
        var invitation = invitations.invite(TENANT, recruiter, "cand-1", "job-new");

        assertThat(memory.messenger.sent).singleElement().satisfies(sent -> {
            assertThat(sent.get("applicationId")).isEqualTo("app-cand-1");
            assertThat(sent.get("templateKey")).isEqualTo("talent-reengagement");
            assertThat(sent.get("variables")).isEqualTo(java.util.Map.of("job.title", "Staff Engineer"));
            assertThat(sent.get("actor")).isEqualTo("recruiter-1");
        });
        assertThat(memory.invitationRows).containsExactly(invitation);
        assertThat(memory.events).singleElement().satisfies(event -> {
            assertThat(event.get("type")).isEqualTo("talent.invited.v1");
            assertThat(event.get("payload").toString())
                    .contains("cand-1", "job-new")
                    .doesNotContain("Priya");
        });
    }

    @Test
    void aSecondInviteInsideTheCooldownIsRefusedAndOneAfterItIsAllowed() {
        invitations.invite(TENANT, recruiter, "cand-1", null);

        assertThatThrownBy(() -> invitations.invite(TENANT, recruiter, "cand-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already invited");
        assertThat(memory.messenger.sent).hasSize(1);

        memory.clock.advance(Duration.ofDays(31));
        invitations.invite(TENANT, recruiter, "cand-1", null);
        assertThat(memory.messenger.sent).hasSize(2);
        assertThat(memory.messenger.sent.getLast().get("variables"))
                .isEqualTo(java.util.Map.of("job.title", "relevant"));
    }

    @Test
    void onlyRecruiterHrAndOrgAdminRolesMayInvite() {
        for (var role : List.of("RECRUITER", "HR_ADMIN", "ORG_ADMIN"))
            assertThat(new Viewer("u", Set.of(role)).mayInvite()).isTrue();
        assertThatThrownBy(() -> invitations.invite(TENANT, new Viewer("u", Set.of("HIRING_MANAGER")), "cand-1", null))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> invitations.invite(TENANT, new Viewer("u", Set.of()), "cand-1", null))
                .isInstanceOf(AccessDenied.class);
        assertThat(memory.messenger.sent).isEmpty();
    }

    @Test
    void aCandidateOutsideThePoolOrWhoOptedOutOfMessagesIsNotInvited() {
        assertThatThrownBy(() -> invitations.invite(TENANT, recruiter, "nobody", null))
                .isInstanceOf(ResourceNotFoundException.class);

        memory.messenger.optedOut = true;
        assertThatThrownBy(() -> invitations.invite(TENANT, recruiter, "cand-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKETING_OPT_OUT");
        assertThat(memory.invitationRows).isEmpty();
        assertThat(memory.events).isEmpty();

        projector.applyConsent(TENANT, "app-cand-1", "cand-1", "Priya Das", false);
        assertThatThrownBy(() -> invitations.invite(TENANT, recruiter, "cand-1", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void consentNeedsAValidReceiptAndIsReadableBack() {
        var consent = memory.consent(projector);

        assertThatThrownBy(() -> consent.set(TENANT, "app-cand-1", "bad", false))
                .isInstanceOf(ReceiptRejected.class);
        assertThatThrownBy(() -> consent.current(TENANT, "app-cand-1", "bad")).isInstanceOf(ReceiptRejected.class);
        assertThat(consent.current(TENANT, "app-cand-1", "valid-receipt").granted())
                .isTrue();

        var revoked = consent.set(TENANT, "app-cand-1", "valid-receipt", false);
        assertThat(revoked.granted()).isFalse();
        assertThat(revoked.consentUpdatedAt()).isEqualTo(Memory.START.toString());
        assertThat(consent.current(TENANT, "app-cand-1", "valid-receipt").granted())
                .isFalse();
        assertThat(memory.vectorRows).isEmpty();

        assertThat(consent.set(TENANT, "app-cand-1", "valid-receipt", true).granted())
                .isTrue();
        assertThat(memory.vectorRows).containsKey("t1:cand-1");
    }

    @Test
    void anInviteIsAuditedWithTheRecruiterAndNoPersonalData() {
        var invitation = invitations.invite(TENANT, recruiter, "cand-1", "job-new");

        assertThat(memory.audit.payloads()).singleElement().satisfies(payload -> {
            assertThat(payload).containsEntry("action", "TALENT_INVITED");
            assertThat(payload.get("actor")).isEqualTo(java.util.Map.of("type", "USER", "id", "recruiter-1"));
            assertThat(payload.get("resource"))
                    .isEqualTo(java.util.Map.of("type", "Invitation", "id", invitation.id()));
            assertThat(payload).containsEntry("candidateId", "cand-1").containsEntry("applicationId", "app-cand-1");
            assertThat(payload.get("details"))
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("jobId", "job-new");
        });
        assertThat(memory.audit.rawJson().getFirst()).doesNotContain("Priya").doesNotContain("Staff Engineer");
    }

    @Test
    void aRefusedInviteIsNotAudited() {
        assertThatThrownBy(() -> invitations.invite(TENANT, new Viewer("u", Set.of()), "cand-1", null))
                .isInstanceOf(AccessDenied.class);

        assertThat(memory.audit.payloads()).isEmpty();
    }

    @Test
    void consentChangesAreAuditedTwiceAsTheCandidateWithThePoolPurpose() {
        var consent = memory.consent(projector);

        consent.set(TENANT, "app-cand-1", "valid-receipt", false);
        consent.set(TENANT, "app-cand-1", "valid-receipt", true);

        var payloads = memory.audit.payloads();
        assertThat(payloads)
                .extracting(p -> p.get("action"))
                .containsExactly(
                        "TALENT_CONSENT_REVOKED", "CONSENT_REVOKED", "TALENT_CONSENT_GRANTED", "CONSENT_GRANTED");
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload.get("actor")).isEqualTo(java.util.Map.of("type", "CANDIDATE", "id", "cand-1"));
            assertThat(payload.get("resource")).isEqualTo(java.util.Map.of("type", "TalentProfile", "id", "cand-1"));
            assertThat(payload.get("details")).isEqualTo(java.util.Map.of("purpose", "TALENT_POOL"));
        });
        assertThat(memory.audit.rawJson()).noneMatch(json -> json.contains("Priya"));
    }

    @Test
    void aBrokenAuditStoreNeverBlocksInvitationsOrConsent() {
        var broken = new Memory();
        var brokenProjector = broken.projector();
        broken.pooled(brokenProjector, "cand-1", "Priya Das", "job-0", "Backend Engineer", "Java engineer.");
        var failing = AuditProbe.broken().recorder();

        var invitation = new InvitationService(
                        broken.profiles,
                        broken.invitations,
                        broken.jobs,
                        broken.messenger,
                        broken.outbox,
                        broken.settings,
                        broken.clock,
                        failing)
                .invite(TENANT, recruiter, "cand-1", null);
        var consent = new ConsentService(broken.applications, broken.profiles, brokenProjector, failing)
                .set(TENANT, "app-cand-1", "valid-receipt", false);

        assertThat(invitation).isNotNull();
        assertThat(consent.granted()).isFalse();
    }
}
