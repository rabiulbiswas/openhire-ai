package com.openhire.offer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferTypes.*;
import com.openhire.offer.service.port.OfferPorts.OfferRepository;
import com.openhire.platform.web.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OfferApprovalInboxTest {
    final OfferRepository repository = mock(OfferRepository.class);
    final OfferApplicationService workflows = mock(OfferApplicationService.class);
    final OfferApprovalInbox inbox = new OfferApprovalInbox(repository, workflows);
    final Viewer owner = viewer("recruiter", "RECRUITER");
    final Viewer manager = viewer("manager", "HIRING_MANAGER");
    final Viewer hr = viewer("hr", "HR_ADMIN");
    final Viewer finance = viewer("finance", "ORG_ADMIN");
    final Instant now = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void projectsCandidateJobCreatorAndExactOfferTermsWithoutUnitConversion() {
        var offer = offer();
        when(repository.find("tenant-a", offer.id())).thenReturn(Optional.of(offer));
        var detail = inbox.get("tenant-a", manager, offer.id());
        assertThat(detail.id()).isEqualTo("offer:" + offer.id());
        assertThat(detail.title()).isEqualTo("Rahul Ahmed, Senior Java Backend Engineer");
        assertThat(detail.requestedBy()).isEqualTo("recruiter");
        assertThat(detail.createdAt()).isEqualTo(now);
        assertThat(detail.offerAction().canDecide()).isTrue();
        assertThat(detail.terms())
                .extracting(t -> t.value())
                .containsExactly(
                        "INR 44,00,000 (band 38,00,000 to 48,00,000)", "INR 1,00,000", "INR 2,00,000", "Nov 2, 2026");
        assertThat(detail.viewerDecision()).isNull();
    }

    @Test
    void usesRecordedActorAndTimeRatherThanClaimingEveryViewerApproved() {
        var offer = offer();
        offer.approve("s1", manager, now.plusSeconds(60));
        when(repository.find("tenant-a", offer.id())).thenReturn(Optional.of(offer));
        var detail = inbox.get("tenant-a", manager, offer.id());
        assertThat(detail.viewerDecision().at()).isEqualTo(now.plusSeconds(60));
        assertThat(detail.offerAction().canDecide()).isFalse();
        assertThat(detail.steps().getFirst().decidedBy()).isEqualTo(manager.subject());
        assertThat(inbox.get("tenant-a", owner, offer.id()).viewerDecision()).isNull();
    }

    @Test
    void onlyCurrentEligibleActorMayDecideAndVersionIsPassedToAuthoritativeWorkflow() {
        var offer = offer();
        when(repository.find("tenant-a", offer.id())).thenReturn(Optional.of(offer));
        assertThatThrownBy(() -> inbox.decide("tenant-a", owner, offer.id(), "s1", 1, null, true))
                .isInstanceOf(OfferApplicationService.AccessDenied.class);
        assertThatThrownBy(() -> inbox.decide("tenant-a", manager, offer.id(), "s1", 2, null, true))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(workflows);
        inbox.decide("tenant-a", manager, offer.id(), "s1", 1, null, true);
        verify(workflows).approve("tenant-a", manager, offer.id(), "s1", 1);
        offer.approve("s1", manager, now);
        assertThat(OfferApprovalInbox.canDecide(offer, manager)).isFalse();
        assertThat(OfferApprovalInbox.canDecide(offer, hr)).isTrue();
        offer.approve("s2", hr, now);
        assertThat(OfferApprovalInbox.canDecide(offer, finance)).isTrue();
    }

    @Test
    void listAndCountsAreTenantScopedAndBoundedWithoutReturningFullOfferDocuments() {
        var offer = offer();
        when(repository.approvalInbox("tenant-a", owner, "requested_by_me", 201))
                .thenReturn(java.util.Collections.nCopies(201, offer));
        var page = inbox.list("tenant-a", owner, "requested_by_me");
        assertThat(page.data()).hasSize(200);
        assertThat(page.hasMore()).isTrue();
        when(repository.countApprovalInbox("tenant-a", owner, "requested_by_me"))
                .thenReturn(500L);
        assertThat(inbox.counts("tenant-a", owner).requestedByMe()).isEqualTo(500);
        verify(repository).countApprovalInbox("tenant-a", owner, "waiting_on_me");
        verify(repository).countApprovalInbox("tenant-a", owner, "completed");
        assertThatThrownBy(() -> inbox.get("tenant-b", owner, offer.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository).find("tenant-b", offer.id());
    }

    @Test
    void candidateAndEmployeeCannotReadCompensationInbox() {
        assertThatThrownBy(() -> inbox.list("tenant-a", viewer("employee", "EMPLOYEE"), "requested_by_me"))
                .isInstanceOf(OfferApplicationService.AccessDenied.class);
        verifyNoInteractions(repository);
    }

    private Offer offer() {
        var terms = new Terms(
                "INR",
                new BigDecimal("4400000"),
                BigDecimal.ZERO,
                new BigDecimal("200000"),
                "2026-11-02",
                null,
                "Manager",
                "Pune",
                "2026-10-31",
                "Engineer");
        var version = new OfferVersion(1, "default", 1, terms, "", "", "sha", owner.subject(), now, false, false);
        var o = Offer.create(
                "tenant-a",
                "app-1",
                "candidate-1",
                "Rahul Ahmed",
                "job-1",
                "Senior Java Backend Engineer",
                owner.subject(),
                new Band("INR", new BigDecimal("3800000"), new BigDecimal("4800000")),
                version,
                now);
        o.assignApprovers(java.util.Map.of("HIRING_MANAGER", "manager", "HR_ADMIN", "hr", "FINANCE", "finance"));
        o.submit(now);
        return o;
    }

    private static Viewer viewer(String id, String role) {
        return new Viewer(id, id + "@example.test", id, Set.of(role));
    }
}
