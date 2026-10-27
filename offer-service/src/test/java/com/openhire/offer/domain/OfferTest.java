package com.openhire.offer.domain;

import static com.openhire.offer.domain.Fixtures.BAND;
import static com.openhire.offer.domain.Fixtures.NOW;
import static com.openhire.offer.domain.Fixtures.approved;
import static com.openhire.offer.domain.Fixtures.draft;
import static com.openhire.offer.domain.Fixtures.finance;
import static com.openhire.offer.domain.Fixtures.hiringManager;
import static com.openhire.offer.domain.Fixtures.hr;
import static com.openhire.offer.domain.Fixtures.sent;
import static com.openhire.offer.domain.Fixtures.terms;
import static com.openhire.offer.domain.Fixtures.version;
import static com.openhire.offer.domain.Fixtures.viewer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.offer.domain.OfferTypes.PendingEvent;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.StepStatus;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OfferTest {
    private static final Duration WEEK = Duration.ofDays(7);

    @SuppressWarnings("unchecked")
    private static List<PendingEvent> events(Offer offer) throws ReflectiveOperationException {
        Field field = Offer.class.getDeclaredField("events");
        field.setAccessible(true);
        return (List<PendingEvent>) field.get(offer);
    }

    private static List<String> types(Offer offer) throws ReflectiveOperationException {
        return events(offer).stream().map(PendingEvent::eventType).toList();
    }

    @Test
    void aNewOfferIsADraftWithACreatedEvent() throws Exception {
        var offer = draft("24");

        assertThat(offer.status()).isEqualTo(Status.DRAFT);
        assertThat(offer.latest().versionNo()).isEqualTo(1);
        assertThat(types(offer)).containsExactly("offer.created.v1");
        assertThat(events(offer).getFirst().payload())
                .containsEntry("applicationId", "app-1")
                .containsEntry("ownerId", "recruiter");
    }

    @Test
    void approvalRunsStepByStepAndAddsFinanceAboveTheMidpoint() throws Exception {
        var offer = draft("27");
        offer.submit(NOW);

        assertThat(offer.status()).isEqualTo(Status.PENDING_APPROVAL);
        assertThat(offer.approvalSteps())
                .extracting(s -> s.role() + ":" + s.status())
                .containsExactly("HIRING_MANAGER:CURRENT", "HR_ADMIN:PENDING", "FINANCE:PENDING");
        assertThat(offer.approvalNote()).contains("Finance joined");

        offer.approve("s1", hiringManager(), NOW);
        offer.approve("s2", hr(), NOW);
        assertThat(offer.status()).isEqualTo(Status.PENDING_APPROVAL);
        offer.approve("s3", finance(), NOW);

        assertThat(offer.status()).isEqualTo(Status.APPROVED);
        assertThat(types(offer))
                .containsExactly(
                        "offer.created.v1",
                        "offer.approval.requested.v1",
                        "offer.approval.step-approved.v1",
                        "offer.approval.requested.v1",
                        "offer.approval.step-approved.v1",
                        "offer.approval.requested.v1",
                        "offer.approved.v1");
        assertThat(events(offer).get(1).payload()).containsEntry("currentApprovers", List.of("hm"));
    }

    @Test
    void theRequesterCannotApproveAndNobodyApprovesTwoSteps() {
        var offer = draft("24");
        offer.assignApprovers(Map.of("HIRING_MANAGER", "both", "HR_ADMIN", "both"));
        offer.submit(NOW);

        assertThatThrownBy(() -> offer.approve("s1", viewer("recruiter", "HIRING_MANAGER"), NOW))
                .hasMessageContaining("cannot approve an offer you created");
        offer.approve("s1", viewer("both", "HIRING_MANAGER", "HR_ADMIN"), NOW);
        assertThatThrownBy(() -> offer.approve("s2", viewer("both", "HIRING_MANAGER", "HR_ADMIN"), NOW))
                .hasMessageContaining("different person");
    }

    @Test
    void onlyTheNamedRoleHolderMayApproveAndSuperAdminsCannotBypassAssignment() {
        var offer = draft("24");
        offer.submit(NOW);

        assertThatThrownBy(() -> offer.approve("s1", viewer("x", "RECRUITER"), NOW))
                .hasMessageContaining("Hiring manager");
        assertThatThrownBy(() -> offer.approve("s2", hr(), NOW)).hasMessageContaining("not awaiting approval");
        assertThatThrownBy(() -> offer.approve("s1", viewer("root", "SUPER_ADMIN"), NOW))
                .hasMessageContaining("assigned");
        assertThatThrownBy(() -> offer.approve("s1", viewer("other-manager", "HIRING_MANAGER"), NOW))
                .hasMessageContaining("assigned");
        offer.approve("s1", hiringManager(), NOW);
        assertThat(offer.approvalSteps().getFirst().status()).isEqualTo(StepStatus.DONE);
    }

    @Test
    void approvingTheSameStepTwiceIsHarmlessForTheSamePerson() throws Exception {
        var offer = draft("24");
        offer.submit(NOW);
        offer.approve("s1", hiringManager(), NOW);
        int before = events(offer).size();

        assertThat(offer.approve("s1", hiringManager(), NOW)).isFalse();
        assertThat(events(offer)).hasSize(before);
        assertThatThrownBy(() -> offer.approve("s1", viewer("other", "HIRING_MANAGER"), NOW))
                .hasMessageContaining("not awaiting");
    }

    @Test
    void rejectionNeedsAReasonAndReturnsTheOfferToDraft() throws Exception {
        var offer = draft("24");
        offer.submit(NOW);

        assertThatThrownBy(() -> offer.reject("s1", hiringManager(), " ", NOW)).hasMessageContaining("Say why");
        offer.reject("s1", hiringManager(), "Too high for the level", NOW);

        assertThat(offer.status()).isEqualTo(Status.DRAFT);
        assertThat(offer.approvalSteps()).isEmpty();
        assertThat(offer.approvalNote()).contains("Hiring manager rejected").contains("Too high");
        assertThat(types(offer)).contains("offer.approval.rejected.v1");
    }

    @Test
    void sendingNeedsApprovalFreezesTheVersionAndIsIdempotent() throws Exception {
        assertThatThrownBy(() -> draft("24").send(NOW.plus(WEEK), "u", NOW)).hasMessageContaining("approved");
        var offer = approved("24");

        assertThat(offer.send(NOW.plus(WEEK), "https://p/offer#token=a", NOW)).isTrue();
        assertThat(offer.send(NOW.plus(WEEK), "https://p/offer#token=b", NOW)).isFalse();

        assertThat(offer.status()).isEqualTo(Status.SENT);
        assertThat(offer.sentVersion().sent()).isTrue();
        assertThat(types(offer).stream().filter("offer.sent.v1"::equals)).hasSize(1);
    }

    @Test
    void theFirstViewMarksViewedOnce() throws Exception {
        var offer = sent("24");
        offer.markViewed(NOW);
        offer.markViewed(NOW);

        assertThat(offer.status()).isEqualTo(Status.VIEWED);
        assertThat(types(offer).stream().filter("offer.viewed.v1"::equals)).hasSize(1);
    }

    @Test
    void acceptingNeedsTheExactNameAndPublishesTheSentTerms() throws Exception {
        var offer = sent("24");

        assertThatThrownBy(() -> offer.accept("Someone Else", NOW)).hasMessageContaining("full name");
        assertThat(offer.accept("  priya   DAS ", NOW)).isTrue();
        assertThat(offer.accept("Priya Das", NOW)).isFalse();

        assertThat(offer.status()).isEqualTo(Status.ACCEPTED);
        var accepted = events(offer).stream()
                .filter(e -> e.eventType().equals("offer.accepted.v1"))
                .toList();
        assertThat(accepted).hasSize(1);
        @SuppressWarnings("unchecked")
        var terms = (Map<String, Object>) accepted.getFirst().payload().get("terms");
        assertThat(terms).containsEntry("joiningDate", "2026-11-16").containsEntry("designation", "Senior Engineer");
        assertThat(terms.get("base")).hasToString("24");
    }

    @Test
    void nothingCanBeAnsweredAfterTheDeadline() {
        var offer = sent("24");
        var late = NOW.plus(WEEK).plusSeconds(1);

        assertThat(offer.canRespond(late)).isFalse();
        assertThatThrownBy(() -> offer.accept("Priya Das", late)).hasMessageContaining("expired");
        assertThatThrownBy(() -> offer.negotiate("More?", late)).hasMessageContaining("expired");
        assertThat(offer.expire(late)).isTrue();
        assertThat(offer.status()).isEqualTo(Status.EXPIRED);
        assertThat(offer.expire(late)).isFalse();
    }

    @Test
    void declineRecordsTheReasonAndIsIdempotent() throws Exception {
        var offer = sent("24");

        assertThat(offer.decline("Accepted elsewhere", NOW)).isTrue();
        assertThat(offer.decline("Accepted elsewhere", NOW)).isFalse();
        assertThat(offer.status()).isEqualTo(Status.DECLINED);
        assertThat(offer.responseReason()).isEqualTo("Accepted elsewhere");
        assertThatThrownBy(() -> offer.accept("Priya Das", NOW)).hasMessageContaining("no longer open");
        assertThat(types(offer).stream().filter("offer.declined.v1"::equals)).hasSize(1);
    }

    @Test
    void negotiationThenAnEditWithoutNewCompensationNeedsOnlyAResend() throws Exception {
        var offer = sent("24");
        offer.negotiate("Could I start on 30 November?", NOW);
        assertThat(offer.status()).isEqualTo(Status.NEGOTIATING);
        assertThat(offer.candidateNote()).contains("30 November");

        var later = new OfferTypes.Terms(
                "INR",
                offer.latest().terms().base(),
                offer.latest().terms().variable(),
                offer.latest().terms().bonus(),
                "2026-11-30",
                "candidate asked",
                "Meera Nair",
                "Bengaluru",
                "2026-10-22",
                "Senior Engineer");
        assertThat(offer.revise(version(later), BAND, NOW)).isTrue();

        assertThat(offer.status()).isEqualTo(Status.APPROVED);
        assertThat(offer.latest().versionNo()).isEqualTo(2);
        assertThat(offer.versions().getFirst().sent()).isTrue();
        assertThat(offer.revising()).isTrue();
        assertThat(types(offer)).contains("offer.revised.v1");
        assertThat(events(offer).getLast().payload()).containsEntry("reapprovalRequired", false);
    }

    @Test
    void aCompensationChangeAfterApprovalRestartsTheChain() throws Exception {
        var offer = approved("24");
        offer.revise(version(terms("28")), BAND, NOW);

        assertThat(offer.status()).isEqualTo(Status.PENDING_APPROVAL);
        assertThat(offer.approvalSteps()).hasSize(3);
        assertThat(offer.approvalSteps().getFirst().status()).isEqualTo(StepStatus.CURRENT);
        assertThat(events(offer).stream()
                        .filter(e -> e.eventType().equals("offer.revised.v1"))
                        .findFirst()
                        .orElseThrow()
                        .payload())
                .containsEntry("reapprovalRequired", true);
    }

    @Test
    void editingADraftReplacesItsUnfrozenVersionButASubmittedOneIsKept() {
        var offer = draft("24");
        offer.revise(version(terms("25")), BAND, NOW);
        assertThat(offer.versions()).hasSize(1);
        assertThat(offer.latest().terms().base()).hasToString("25");

        offer.submit(NOW);
        assertThat(offer.latest().frozen()).isTrue();
        offer.revise(version(terms("26")), BAND, NOW);
        assertThat(offer.versions()).hasSize(2);
        assertThat(offer.versions().getFirst().terms().base()).hasToString("25");
    }

    @Test
    void anUnchangedSaveDoesNothing() {
        var offer = draft("24");
        assertThat(offer.revise(version(terms("24")), BAND, NOW)).isFalse();
    }

    @Test
    void rescindingWorksFromOpenStatesButNotAfterAcceptance() throws Exception {
        var open = sent("24");
        open.rescind("Role closed", NOW);
        assertThat(open.status()).isEqualTo(Status.RESCINDED);
        assertThat(types(open)).contains("offer.rescinded.v1");
        open.rescind("again", NOW); // idempotent

        var accepted = sent("24");
        accepted.accept("Priya Das", NOW);
        assertThatThrownBy(() -> accepted.rescind("late", NOW)).hasMessageContaining("accepted");
        assertThatThrownBy(() -> accepted.revise(version(terms("25")), BAND, NOW))
                .hasMessageContaining("accepted");
    }

    @Test
    void aClosedOfferCanBeStartedAgain() {
        var offer = sent("24");
        offer.decline(null, NOW);
        assertThat(offer.status()).isEqualTo(Status.DECLINED);

        offer.reopen("recruiter-2", BAND, version(terms("26")), NOW);

        assertThat(offer.status()).isEqualTo(Status.DRAFT);
        assertThat(offer.ownerId()).isEqualTo("recruiter-2");
        assertThat(offer.expiresAt()).isNull();
        assertThatThrownBy(() -> offer.reopen("x", BAND, version(terms("26")), NOW))
                .hasMessageContaining("in progress");
    }

    /** Random command sequences must only ever follow the documented state machine. */
    @Test
    void randomCommandSequencesOnlyMakeLegalTransitions() throws Exception {
        var legal = new EnumMap<Status, Set<Status>>(Status.class);
        legal.put(Status.DRAFT, EnumSet.of(Status.DRAFT, Status.PENDING_APPROVAL));
        legal.put(Status.PENDING_APPROVAL, EnumSet.of(Status.PENDING_APPROVAL, Status.DRAFT, Status.APPROVED));
        legal.put(Status.APPROVED, EnumSet.of(Status.APPROVED, Status.SENT, Status.PENDING_APPROVAL, Status.RESCINDED));
        legal.put(
                Status.SENT,
                EnumSet.of(
                        Status.SENT,
                        Status.VIEWED,
                        Status.NEGOTIATING,
                        Status.ACCEPTED,
                        Status.DECLINED,
                        Status.EXPIRED,
                        Status.RESCINDED,
                        Status.APPROVED,
                        Status.PENDING_APPROVAL));
        legal.put(
                Status.VIEWED,
                EnumSet.of(
                        Status.VIEWED,
                        Status.NEGOTIATING,
                        Status.ACCEPTED,
                        Status.DECLINED,
                        Status.EXPIRED,
                        Status.RESCINDED,
                        Status.APPROVED,
                        Status.PENDING_APPROVAL));
        legal.put(
                Status.NEGOTIATING,
                EnumSet.of(
                        Status.NEGOTIATING,
                        Status.ACCEPTED,
                        Status.DECLINED,
                        Status.EXPIRED,
                        Status.RESCINDED,
                        Status.APPROVED,
                        Status.PENDING_APPROVAL));
        for (var terminal : List.of(Status.ACCEPTED, Status.DECLINED, Status.EXPIRED, Status.RESCINDED))
            legal.put(terminal, EnumSet.of(terminal));
        legal.get(Status.DRAFT).add(Status.RESCINDED);
        legal.get(Status.PENDING_APPROVAL).add(Status.RESCINDED);

        var visited = EnumSet.noneOf(Status.class);
        var counts = new EnumMap<Status, Integer>(Status.class);
        for (long seed = 1; seed <= 300; seed++) {
            var random = new Random(seed);
            var offer = draft("27");
            var clock = NOW;
            for (int step = 0; step < 40; step++) {
                var before = offer.status();
                var after = before;
                try {
                    if (random.nextInt(10) < 6) clock = guided(offer, random, clock);
                    else {
                        switch (random.nextInt(14)) {
                            case 0 -> offer.submit(clock);
                            case 1 ->
                                offer.approve(
                                        "s" + (1 + random.nextInt(3)),
                                        viewer("hm" + random.nextInt(4), "HIRING_MANAGER", "HR_ADMIN", "ORG_ADMIN"),
                                        clock);
                            case 2 ->
                                offer.reject("s1", viewer("r" + random.nextInt(4), "HIRING_MANAGER"), "no", clock);
                            case 3 -> offer.send(clock.plus(WEEK), "u", clock);
                            case 4 -> offer.markViewed(clock);
                            case 5 -> offer.negotiate("change", clock);
                            case 6 -> offer.accept("Priya Das", clock);
                            case 7 -> offer.decline("no", clock);
                            case 8 -> offer.expire(clock);
                            case 9 -> {
                                if (random.nextInt(6) == 0) offer.rescind("x", clock);
                            }
                            case 10 ->
                                offer.revise(version(terms(String.valueOf(20 + random.nextInt(11)))), BAND, clock);
                            case 11 -> clock = clock.plus(Duration.ofDays(random.nextInt(5)));
                            case 12 -> offer.canRespond(clock);
                            default -> offer.revising();
                        }
                    }
                } catch (IllegalStateException | IllegalArgumentException rejected) {
                    // A refused command must leave the status alone.
                    assertThat(offer.status()).as("seed %d step %d", seed, step).isEqualTo(before);
                }
                after = offer.status();
                visited.add(after);
                counts.merge(after, 1, Integer::sum);
                assertThat(legal.get(before))
                        .as("seed %d: %s -> %s", seed, before, after)
                        .contains(after);
                if (offer.status() == Status.ACCEPTED)
                    assertThat(types(offer).stream().filter("offer.accepted.v1"::equals))
                            .hasSize(1);
                assertThat(new ArrayList<>(offer.versions())
                                .stream()
                                        .map(OfferTypes.OfferVersion::versionNo)
                                        .toList())
                        .as("version numbers are strictly increasing")
                        .isSorted();
            }
        }
        assertThat(visited)
                .as("the random walk should reach every state, visits: " + counts)
                .containsAll(EnumSet.allOf(Status.class));
    }

    /** The command a person would naturally issue next; refusals are swallowed so the invariant check still runs. */
    private static java.time.Instant guided(Offer offer, Random random, java.time.Instant clock) {
        try {
            switch (offer.status()) {
                case DRAFT -> offer.submit(clock);
                case PENDING_APPROVAL -> {
                    var step = offer.currentStep();
                    offer.approve(
                            step.id(),
                            viewer(
                                    offer.approvalAssignees().get(step.role()),
                                    "FINANCE".equals(step.role()) ? "ORG_ADMIN" : step.role()),
                            clock);
                }
                case APPROVED -> offer.send(clock.plus(WEEK), "u", clock);
                case SENT, VIEWED, NEGOTIATING -> {
                    switch (random.nextInt(6)) {
                        case 0 -> offer.markViewed(clock);
                        case 1 -> offer.negotiate("change", clock);
                        case 2 -> offer.accept("Priya Das", clock);
                        case 3 -> offer.decline("no", clock);
                        case 4 -> {
                            clock = clock.plus(WEEK).plusSeconds(1);
                            offer.expire(clock);
                        }
                        default -> offer.revise(version(terms(String.valueOf(20 + random.nextInt(11)))), BAND, clock);
                    }
                }
                default -> {}
            }
        } catch (IllegalStateException | IllegalArgumentException refused) {
            // refused commands leave the state alone; the caller asserts that
        }
        return clock;
    }
}
