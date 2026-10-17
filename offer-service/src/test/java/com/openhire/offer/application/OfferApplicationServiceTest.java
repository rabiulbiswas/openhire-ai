package com.openhire.offer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openhire.offer.application.OfferApplicationService.AccessDenied;
import com.openhire.offer.application.OfferApplicationService.TermsInput;
import com.openhire.offer.application.port.OfferPorts.ApplicationDirectory;
import com.openhire.offer.application.port.OfferPorts.ApplicationInfo;
import com.openhire.offer.application.port.OfferPorts.JobDirectory;
import com.openhire.offer.application.port.OfferPorts.JobInfo;
import com.openhire.offer.application.port.OfferPorts.OfferRepository;
import com.openhire.offer.application.port.OfferPorts.OfferTokens;
import com.openhire.offer.application.port.OfferPorts.TemplateStore;
import com.openhire.offer.application.port.OfferPorts.TokenClaims;
import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferClause;
import com.openhire.offer.domain.OfferTemplate;
import com.openhire.offer.domain.OfferTypes.Band;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.Viewer;
import com.openhire.platform.audit.AuditRecorder;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class OfferApplicationServiceTest {
    private static final String TENANT = "t1";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T05:00:00Z"));
    private final MemoryOffers offers = new MemoryOffers();
    private final MemoryTemplates templates = new MemoryTemplates();
    private String stage = "OFFER";
    private OfferApplicationService service;
    private final AuditProbe audit = new AuditProbe();
    private ApplicationDirectory applications;
    private JobDirectory jobs;
    private OfferTokens tokens;
    private OfferSettings settings;

    private final Viewer recruiter = viewer("recruiter", "RECRUITER");
    private final Viewer manager = viewer("hm", "HIRING_MANAGER");
    private final Viewer hr = viewer("hr", "HR_ADMIN");

    @BeforeEach
    void setUp() {
        applications = new ApplicationDirectory() {
            public ApplicationInfo application(String tenantId, String applicationId) {
                return new ApplicationInfo(
                        applicationId, "cand-1", "Priya Das", "priya@x.test", "job-1", "Senior Engineer", stage);
            }

            public void verifyReceipt(String tenantId, String applicationId, String receipt) {
                if (!"good".equals(receipt)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
        };
        jobs = (tenantId, jobId) -> new JobInfo(
                jobId,
                "Senior Engineer",
                "Bengaluru",
                "Meera Nair",
                new Band("INR", new BigDecimal("20"), new BigDecimal("30")));
        tokens = new OfferTokens() {
            public String issue(String tenantId, String offerId, Instant expiresAt) {
                return String.join("~", tenantId, offerId, Long.toString(expiresAt.getEpochSecond()));
            }

            public TokenClaims verify(String token, Instant now) {
                var parts = token.split("~");
                if (parts.length != 3) throw new IllegalArgumentException("Invalid offer link");
                var expiry = Instant.ofEpochSecond(Long.parseLong(parts[2]));
                if (!expiry.isAfter(now)) throw new IllegalArgumentException("This offer link has expired");
                return new TokenClaims(parts[0], parts[1], expiry);
            }
        };
        settings = new OfferSettings(
                7, Duration.ofDays(30), "http://portal.test", "Northwind Payments", ZoneId.of("Asia/Kolkata"));
        service = serviceAuditedBy(audit.recorder);
    }

    private OfferApplicationService serviceAuditedBy(AuditRecorder recorder) {
        return new OfferApplicationService(offers, templates, applications, jobs, tokens, settings, clock, recorder);
    }

    private static Viewer viewer(String subject, String... roles) {
        return new Viewer(subject, subject + "@x.test", subject, Set.of(roles));
    }

    private String token(String url) {
        return URLDecoder.decode(url.substring(url.indexOf("#token=") + 7), StandardCharsets.UTF_8);
    }

    private String sentToken(String offerId) {
        var url = offers.byId(offerId).latestSentUrl;
        return token(url);
    }

    private String approvedOffer(TermsInput terms) {
        var created = service.create(TENANT, recruiter, "app-1", null, terms, "Welcome");
        service.submit(TENANT, recruiter, created.id());
        service.approve(TENANT, manager, created.id(), "s1");
        var detail = service.approve(TENANT, hr, created.id(), "s2");
        if (detail.approvalSteps().size() == 3)
            detail = service.approve(TENANT, viewer("fin", "ORG_ADMIN"), created.id(), "s3");
        assertThat(detail.status()).isEqualTo("APPROVED");
        return created.id();
    }

    private TermsInput base(String value) {
        return new TermsInput(null, new BigDecimal(value), null, null, null, null, null, null, null, null);
    }

    @Test
    void anOfferIsOnlyCreatedAfterAHireDecisionAndIsPrefilledFromTheJob() {
        stage = "INTERVIEWING";
        assertThatThrownBy(() -> service.create(TENANT, recruiter, "app-1", null, null, null))
                .hasMessageContaining("hire decision");

        stage = "OFFER";
        var offer = service.create(TENANT, recruiter, "app-1", null, null, "Welcome aboard");

        assertThat(offer.status()).isEqualTo("DRAFT");
        assertThat(offer.terms().designation()).isEqualTo("Senior Engineer");
        assertThat(offer.terms().location()).isEqualTo("Bengaluru");
        assertThat(offer.terms().base()).isEqualByComparingTo("25"); // band midpoint
        assertThat(offer.terms().currency()).isEqualTo("INR");
        assertThat(offer.terms().joiningDate()).isEqualTo("2026-11-14");
        assertThat(offer.terms().expiryDate()).isEqualTo("2026-10-22");
        assertThat(offer.terms().withinBand()).isTrue();
        assertThat(offer.band().max()).isEqualByComparingTo("30");
        assertThat(offer.templateName()).isEqualTo("Standard offer letter");
        assertThat(offer.detail()).isEqualTo("Draft, not yet submitted");
    }

    @Test
    void anOfferWithoutAReportingLineOrLocationCannotBeSubmitted() {
        var created = service.create(
                TENANT,
                recruiter,
                "app-1",
                null,
                new TermsInput(null, null, null, null, null, null, " ", "", null, null),
                null);

        assertThatThrownBy(() -> service.submit(TENANT, recruiter, created.id()))
                .hasMessageContaining("reports to");
        service.update(
                TENANT,
                recruiter,
                created.id(),
                new TermsInput(null, null, null, null, null, null, "Meera Nair", "", null, null),
                null);
        assertThatThrownBy(() -> service.submit(TENANT, recruiter, created.id()))
                .hasMessageContaining("location");
        service.update(
                TENANT,
                recruiter,
                created.id(),
                new TermsInput(null, null, null, null, null, null, null, "Bengaluru", null, null),
                null);
        assertThat(service.submit(TENANT, recruiter, created.id()).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void peopleSeeNamesNotAccountIdsOnApprovalsAndVersions() {
        var named = new Viewer("5b1c-uuid", "meera@x.test", "Meera Nair", Set.of("HIRING_MANAGER"));
        var author = new Viewer("9f2d-uuid", "arjun@x.test", "Arjun Mehta", Set.of("RECRUITER"));
        var created = service.create(TENANT, author, "app-1", null, null, null);
        service.submit(TENANT, author, created.id());

        var detail = service.approve(TENANT, named, created.id(), "s1");

        assertThat(detail.approvalSteps().getFirst().decidedBy()).isEqualTo("Meera Nair");
        assertThat(detail.versions().getFirst().createdBy()).isEqualTo("Arjun Mehta");
        var unnamed = new Viewer("only-subject", null, null, Set.of("HR_ADMIN"));
        assertThat(unnamed.displayName()).isEqualTo("only-subject");
        assertThat(new Viewer("s", "e@x.test", " ", Set.of()).displayName()).isEqualTo("e@x.test");
    }

    @Test
    void creatingAgainReturnsTheSameOpenOffer() {
        var first = service.create(TENANT, recruiter, "app-1", null, null, null);
        var second = service.create(TENANT, recruiter, "app-1", null, base("22"), null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.terms().base()).isEqualByComparingTo("25");
        assertThat(offers.saves).isEqualTo(1);
    }

    @Test
    void baseOutsideTheBandIsRefused() {
        assertThatThrownBy(() -> service.create(TENANT, recruiter, "app-1", null, base("45"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the job's band");
    }

    @Test
    void onlyTheRightPeopleCanCreateApproveSendOrRescind() {
        assertThatThrownBy(() -> service.create(TENANT, manager, "app-1", null, null, null))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.list(TENANT, viewer("x", "INTERVIEWER"), null))
                .isInstanceOf(AccessDenied.class);
        var id = approvedOffer(null);
        assertThatThrownBy(() -> service.send(TENANT, manager, id)).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.rescind(TENANT, recruiter, id, "x")).isInstanceOf(AccessDenied.class);
        service.rescind(TENANT, hr, id, "Role on hold");
        assertThat(service.get(TENANT, hr, id).detail()).isEqualTo("Rescinded: Role on hold");
    }

    @Test
    void aHighBaseAddsFinanceAndSendingBuildsTheCandidateLink() {
        var id = approvedOffer(base("28"));
        var detail = service.get(TENANT, recruiter, id);
        assertThat(detail.approvalNote()).contains("Finance joined");
        assertThat(detail.approvalSteps())
                .extracting(s -> s.approver() + ":" + s.status())
                .containsExactly("Hiring manager:DONE", "HR head:DONE", "Finance:DONE");

        var sent = service.send(TENANT, recruiter, id);

        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.expiresAt()).isEqualTo("2026-10-22T18:30:00Z"); // end of 22 Oct in Asia/Kolkata
        assertThat(offers.byId(id).latestSentUrl).startsWith("http://portal.test/offer#token=");
        assertThat(service.send(TENANT, recruiter, id).status()).isEqualTo("SENT"); // idempotent
        assertThat(offers.byId(id).sentEvents()).isEqualTo(1);
    }

    @Test
    void theCandidateViewsThenAcceptsWithTheirName() {
        var id = approvedOffer(null);
        service.send(TENANT, recruiter, id);
        var token = sentToken(id);

        var view = service.publicView(token);
        assertThat(view.status()).isEqualTo("VIEWED");
        assertThat(view.canRespond()).isTrue();
        assertThat(view.companyName()).isEqualTo("Northwind Payments");
        assertThat(view.letterText()).contains("Dear Priya Das,").contains("Base pay: INR 25");
        assertThat(view.terms().designation()).isEqualTo("Senior Engineer");

        assertThatThrownBy(() -> service.publicAccept(token, "Someone Else")).hasMessageContaining("full name");
        var accepted = service.publicAccept(token, "priya das");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.canRespond()).isFalse();
        assertThat(service.publicAccept(token, "Priya Das").status()).isEqualTo("ACCEPTED");
        assertThat(offers.byId(id).acceptedEvents()).isEqualTo(1);
    }

    @Test
    void negotiationLeadsToARevisedOfferThatNeedsApprovalOnlyForCompensation() {
        var id = approvedOffer(null);
        service.send(TENANT, recruiter, id);
        var token = sentToken(id);
        service.publicNegotiate(token, "Could the joining date be 30 November?");
        assertThat(service.get(TENANT, recruiter, id).status()).isEqualTo("NEGOTIATING");

        var moved = service.update(
                TENANT,
                recruiter,
                id,
                new TermsInput(null, null, null, null, "2026-11-30", "candidate asked", null, null, null, null),
                null);
        assertThat(moved.status()).isEqualTo("APPROVED");
        assertThat(moved.version()).isEqualTo(2);
        var whileRevising = service.publicView(token);
        assertThat(whileRevising.status()).isEqualTo("REVISING");
        assertThat(whileRevising.revising()).isTrue();
        assertThat(whileRevising.canRespond()).isFalse();
        assertThat(whileRevising.terms().joiningDate()).isEqualTo("2026-11-14"); // still the sent terms

        service.send(TENANT, recruiter, id);
        assertThat(service.publicView(token).terms().joiningDate()).isEqualTo("2026-11-30");

        var richer = service.update(TENANT, recruiter, id, base("27"), null);
        assertThat(richer.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(richer.approvalSteps()).hasSize(3);
        assertThat(richer.versions()).hasSize(3);
    }

    @Test
    void anExpiredOfferCannotBeAcceptedAndTheSweepExpiresIt() {
        var id = approvedOffer(null);
        service.send(TENANT, recruiter, id);
        var token = sentToken(id);
        clock.advance(Duration.ofDays(8));

        assertThat(service.publicView(token).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> service.publicAccept(token, "Priya Das")).hasMessageContaining("expired");
        assertThat(service.sweep()).isEqualTo(1);
        assertThat(service.get(TENANT, recruiter, id).status()).isEqualTo("EXPIRED");
        assertThat(service.sweep()).isZero();
    }

    @Test
    void aTamperedOrUnsentOfferLinkIsRefused() {
        var id = approvedOffer(null);
        assertThatThrownBy(() -> service.publicView("garbage")).hasMessageContaining("Invalid offer link");
        var unsent = "t1~" + id + "~" + clock.instant().plusSeconds(3600).getEpochSecond();
        assertThatThrownBy(() -> service.publicView(unsent)).hasMessageContaining("not available");
    }

    @Test
    void withdrawingTheApplicationRescindsAnOpenOfferButNeverAnAcceptedOne() {
        var open = approvedOffer(null);
        service.send(TENANT, recruiter, open);
        service.closeForApplication(TENANT, "app-1", "The candidate withdrew their application");
        assertThat(service.get(TENANT, hr, open).detail()).contains("Rescinded");

        offers.clear();
        var accepted = approvedOffer(null);
        service.send(TENANT, recruiter, accepted);
        service.publicAccept(sentToken(accepted), "Priya Das");
        service.closeForApplication(TENANT, "app-1", "x");
        assertThat(service.get(TENANT, hr, accepted).status()).isEqualTo("ACCEPTED");
    }

    @Test
    void aRescindedOfferCanBeStartedAgain() {
        var id = approvedOffer(null);
        service.rescind(TENANT, hr, id, "Wrong band");

        var again = service.create(TENANT, recruiter, "app-1", null, base("22"), null);

        assertThat(again.id()).isEqualTo(id);
        assertThat(again.status()).isEqualTo("DRAFT");
        assertThat(again.version()).isGreaterThan(1);
        assertThat(again.terms().base()).isEqualByComparingTo("22");
    }

    @Test
    void theReceiptEndpointReportsStatusAndIssuesAFreshLink() {
        assertThat(service.receiptStatus(TENANT, "app-1", "good").status()).isEqualTo("NONE");
        var id = approvedOffer(null);
        assertThat(service.receiptStatus(TENANT, "app-1", "good").status()).isEqualTo("NONE"); // approved but unsent

        service.send(TENANT, recruiter, id);
        var status = service.receiptStatus(TENANT, "app-1", "good");

        assertThat(status.status()).isEqualTo("SENT");
        assertThat(status.offerUrl()).startsWith("http://portal.test/offer#token=");
        assertThat(service.publicView(token(status.offerUrl())).canRespond()).isTrue();
        assertThatThrownBy(() -> service.receiptStatus(TENANT, "app-1", "bad"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void templatesAreSeededOnceAndEditsBecomeNewVersions() {
        var list = service.templates(TENANT, recruiter);
        assertThat(list).singleElement().satisfies(t -> {
            assertThat(t.key()).isEqualTo("standard");
            assertThat(t.version()).isEqualTo(1);
        });
        assertThat(service.clauses(TENANT, recruiter)).hasSize(3);

        assertThatThrownBy(() -> service.saveTemplate(TENANT, recruiter, "standard", null, "Hi", List.of()))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> service.saveTemplate(TENANT, hr, "standard", null, "Hi {{secret}}", List.of()))
                .hasMessageContaining("Unknown merge field");
        assertThatThrownBy(() ->
                        service.saveTemplate(TENANT, hr, "standard", null, "Hi {{candidate.name}}", List.of("nope")))
                .hasMessageContaining("Unknown clause");

        var edited = service.saveTemplate(
                TENANT, hr, "standard", "Standard v2", "Hi {{candidate.name}}\n{{clauses}}", List.of("probation"));
        assertThat(edited.version()).isEqualTo(2);
        assertThat(service.templates(TENANT, hr))
                .singleElement()
                .extracting(t -> t.version())
                .isEqualTo(2);
        var offer = service.create(TENANT, recruiter, "app-1", null, null, null);
        assertThat(offer.templateName()).isEqualTo("Standard v2");
    }

    // ---- In-memory ports ------------------------------------------------------------------------

    static final class MemoryOffers implements OfferRepository {
        private final Map<String, Offer> rows = new LinkedHashMap<>();
        int saves;

        void clear() {
            rows.clear();
        }

        Probe byId(String id) {
            return new Probe(rows.get(id));
        }

        public Offer save(Offer offer) {
            saves++;
            rows.put(offer.id(), offer);
            return offer;
        }

        public Optional<Offer> find(String tenantId, String offerId) {
            return Optional.ofNullable(rows.get(offerId));
        }

        public Optional<Offer> findByApplication(String tenantId, String applicationId) {
            return rows.values().stream()
                    .filter(o -> o.applicationId().equals(applicationId))
                    .findFirst();
        }

        public List<Offer> list(String tenantId, Status status, int limit) {
            return rows.values().stream()
                    .filter(o -> status == null || o.status() == status)
                    .limit(limit)
                    .toList();
        }

        public List<Offer> findExpired(Instant now, int limit) {
            return rows.values().stream()
                    .filter(o -> o.status().open()
                            && o.expiresAt() != null
                            && !o.expiresAt().isAfter(now))
                    .limit(limit)
                    .toList();
        }
    }

    /** Reads pending events and the sent link straight from the aggregate. */
    static final class Probe {
        private final Offer offer;
        String latestSentUrl;

        Probe(Offer offer) {
            this.offer = offer;
            this.latestSentUrl = events().stream()
                    .filter(e -> e.eventType().equals("offer.sent.v1"))
                    .reduce((first, second) -> second)
                    .map(e -> (String) e.payload().get("offerUrl"))
                    .orElse(null);
        }

        @SuppressWarnings("unchecked")
        private List<com.openhire.offer.domain.OfferTypes.PendingEvent> events() {
            try {
                var field = Offer.class.getDeclaredField("events");
                field.setAccessible(true);
                return new ArrayList<>((List<com.openhire.offer.domain.OfferTypes.PendingEvent>) field.get(offer));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        long count(String type) {
            return events().stream().filter(e -> e.eventType().equals(type)).count();
        }

        long sentEvents() {
            return count("offer.sent.v1");
        }

        long acceptedEvents() {
            return count("offer.accepted.v1");
        }
    }

    static final class MemoryTemplates implements TemplateStore {
        private final Map<String, OfferTemplate> templates = new LinkedHashMap<>();
        private final Map<String, OfferClause> clauses = new LinkedHashMap<>();

        public Optional<OfferTemplate> template(String tenantId, String key) {
            return Optional.ofNullable(templates.get(key));
        }

        public List<OfferTemplate> templates(String tenantId) {
            return List.copyOf(templates.values());
        }

        public OfferTemplate saveTemplate(OfferTemplate template) {
            templates.put(template.key(), template);
            return template;
        }

        public Optional<OfferClause> clause(String tenantId, String key) {
            return Optional.ofNullable(clauses.get(key));
        }

        public List<OfferClause> clauses(String tenantId) {
            return List.copyOf(clauses.values());
        }

        public OfferClause saveClause(OfferClause clause) {
            clauses.put(clause.key(), clause);
            return clause;
        }

        public void seedTemplate(OfferTemplate template) {
            templates.putIfAbsent(template.key(), template);
        }

        public void seedClause(OfferClause clause) {
            clauses.putIfAbsent(clause.key(), clause);
        }
    }

    @Test
    void everyStaffStepOfTheOfferLifecycleIsAuditedWithTheActorAndNoPersonalData() {
        var created = service.create(TENANT, recruiter, "app-1", null, null, "Welcome Priya");
        var id = created.id();
        service.submit(TENANT, recruiter, id);
        service.approve(TENANT, manager, id, "s1");
        service.approve(TENANT, hr, id, "s2");
        service.send(TENANT, recruiter, id);
        service.rescind(TENANT, hr, id, "Wrong band for Priya");

        assertThat(audit.only("OFFER_CREATED"))
                .contains("\"id\":\"recruiter\"", "\"type\":\"USER\"", "\"type\":\"Offer\"", id, "cand-1")
                .contains("\"applicationId\":\"app-1\"", "\"versionNo\":1");
        assertThat(audit.only("OFFER_SUBMITTED")).contains("\"id\":\"recruiter\"");
        assertThat(audit.records())
                .filteredOn(json -> json.contains("OFFER_APPROVED"))
                .hasSize(2)
                .anyMatch(json -> json.contains("\"id\":\"hm\"") && json.contains("\"stepRole\":\"HIRING_MANAGER\""));
        assertThat(audit.only("OFFER_SENT")).contains("\"id\":\"recruiter\"", "\"versionNo\":1");
        assertThat(audit.only("OFFER_RESCINDED")).contains("\"id\":\"hr\"");
        assertThat(String.join("", audit.records())).doesNotContain("Priya", "priya@x.test", "Wrong band", "Welcome");
    }

    @Test
    void aRejectionIsAuditedWithTheStepRoleButNotTheReason() {
        var id = service.create(TENANT, recruiter, "app-1", null, null, null).id();
        service.submit(TENANT, recruiter, id);

        service.reject(TENANT, manager, id, "s1", "Compensation is too high");

        assertThat(audit.only("OFFER_REJECTED"))
                .contains("\"id\":\"hm\"", "\"stepRole\":\"HIRING_MANAGER\"")
                .doesNotContain("too high");
    }

    @Test
    void aCandidateAcceptingOrDecliningIsAuditedAsTheCandidateOnce() {
        var accepted = approvedOffer(base("25"));
        service.send(TENANT, recruiter, accepted);
        service.publicAccept(sentToken(accepted), "Priya Das");
        service.publicAccept(sentToken(accepted), "Priya Das"); // idempotent replay

        assertThat(audit.only("OFFER_ACCEPTED"))
                .contains("\"type\":\"CANDIDATE\"", "\"id\":\"cand-1\"", accepted)
                .doesNotContain("Priya", "priya@x.test");

        var declinedAudit = new AuditProbe();
        var declining = serviceAuditedBy(declinedAudit.recorder);
        offers.clear();
        var id = declining.create(TENANT, recruiter, "app-1", null, null, null).id();
        declining.submit(TENANT, recruiter, id);
        declining.approve(TENANT, manager, id, "s1");
        declining.approve(TENANT, hr, id, "s2");
        declining.send(TENANT, recruiter, id);
        declining.publicDecline(sentToken(id), "Took a role elsewhere");

        assertThat(declinedAudit.only("OFFER_DECLINED"))
                .contains("\"type\":\"CANDIDATE\"", "\"id\":\"cand-1\"")
                .doesNotContain("elsewhere");
    }

    @Test
    void withdrawingTheApplicationAuditsTheSystemRescission() {
        var id = approvedOffer(base("25"));
        service.send(TENANT, recruiter, id);

        service.closeForApplication(TENANT, "app-1", "The candidate withdrew their application");

        assertThat(audit.only("OFFER_RESCINDED"))
                .contains("\"type\":\"SYSTEM\"", id, "APPLICATION_CLOSED")
                .doesNotContain("withdrew");
    }

    @Test
    void changingATemplateOrClauseIsAuditedWithoutItsText() {
        service.saveTemplate(TENANT, hr, "custom-offer", "Custom", "Dear {{candidate.name}}, secret body", List.of());
        service.saveClause(TENANT, hr, "custom-clause", "Custom clause", "Secret clause wording");

        assertThat(audit.records()).hasSize(2).allMatch(json -> json.contains("OFFER_TEMPLATE_CHANGED"));
        assertThat(audit.records().getFirst()).contains("OfferTemplate", "custom-offer", "\"id\":\"hr\"");
        assertThat(audit.records().getLast()).contains("OfferClause", "custom-clause");
        assertThat(String.join("", audit.records())).doesNotContain("secret", "Secret");
    }

    @Test
    void anAuditFailureNeverBreaksTheOfferAction() {
        var guarded = serviceAuditedBy(new AuditProbe().failing().recorder);

        var id = guarded.create(TENANT, recruiter, "app-1", null, null, null).id();
        guarded.submit(TENANT, recruiter, id);
        guarded.approve(TENANT, manager, id, "s1");
        guarded.approve(TENANT, hr, id, "s2");

        assertThat(guarded.send(TENANT, recruiter, id).status()).isEqualTo("SENT");
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
