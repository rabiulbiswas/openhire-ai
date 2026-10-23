package com.openhire.offer.service;

import com.openhire.offer.domain.CompensationPolicy;
import com.openhire.offer.domain.DefaultTemplates;
import com.openhire.offer.domain.Offer;
import com.openhire.offer.domain.OfferClause;
import com.openhire.offer.domain.OfferRenderer;
import com.openhire.offer.domain.OfferTemplate;
import com.openhire.offer.domain.OfferTypes.OfferVersion;
import com.openhire.offer.domain.OfferTypes.Status;
import com.openhire.offer.domain.OfferTypes.Terms;
import com.openhire.offer.domain.OfferTypes.Viewer;
import com.openhire.offer.dto.OfferViews.BandView;
import com.openhire.offer.dto.OfferViews.ClauseView;
import com.openhire.offer.dto.OfferViews.OfferDetail;
import com.openhire.offer.dto.OfferViews.OfferSummary;
import com.openhire.offer.dto.OfferViews.Page;
import com.openhire.offer.dto.OfferViews.PublicOfferView;
import com.openhire.offer.dto.OfferViews.PublicTerms;
import com.openhire.offer.dto.OfferViews.ReceiptOfferView;
import com.openhire.offer.dto.OfferViews.StepView;
import com.openhire.offer.dto.OfferViews.TemplateView;
import com.openhire.offer.dto.OfferViews.TermsView;
import com.openhire.offer.dto.OfferViews.VersionView;
import com.openhire.offer.service.port.OfferPorts.ApplicationDirectory;
import com.openhire.offer.service.port.OfferPorts.JobDirectory;
import com.openhire.offer.service.port.OfferPorts.OfferRepository;
import com.openhire.offer.service.port.OfferPorts.OfferTokens;
import com.openhire.offer.service.port.OfferPorts.TemplateStore;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class OfferApplicationService {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public static final class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    /** Partial terms from the client; missing fields keep their current (or defaulted) value. */
    public record TermsInput(
            String currency,
            BigDecimal base,
            BigDecimal variable,
            BigDecimal bonus,
            String joiningDate,
            String joiningDateNote,
            String reportsTo,
            String location,
            String expiryDate,
            String designation) {}

    private final OfferRepository offers;
    private final TemplateStore templates;
    private final ApplicationDirectory applications;
    private final JobDirectory jobs;
    private final OfferTokens tokens;
    private final OfferSettings settings;
    private final Clock clock;
    private final AuditRecorder audit;

    public OfferApplicationService(
            OfferRepository offers,
            TemplateStore templates,
            ApplicationDirectory applications,
            JobDirectory jobs,
            OfferTokens tokens,
            OfferSettings settings,
            Clock clock,
            AuditRecorder audit) {
        this.offers = offers;
        this.templates = templates;
        this.applications = applications;
        this.jobs = jobs;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    // ---- Staff reads ----------------------------------------------------------------------------

    public Page<OfferSummary> list(String tenantId, Viewer viewer, String status) {
        requireRead(viewer);
        Status filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = Status.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown status " + status);
            }
        }
        return Page.of(
                offers.list(tenantId, filter, 200).stream().map(this::summary).toList());
    }

    public OfferDetail get(String tenantId, Viewer viewer, String offerId) {
        requireRead(viewer);
        return detail(require(tenantId, offerId));
    }

    public OfferDetail forApplication(String tenantId, Viewer viewer, String applicationId) {
        requireRead(viewer);
        return detail(offers.findByApplication(tenantId, applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("No offer exists for this application")));
    }

    // ---- Staff commands -------------------------------------------------------------------------

    private com.openhire.support.privacy.Tombstones tombstones;

    /** Optional so the service can be built without the privacy framework in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTombstones(com.openhire.support.privacy.Tombstones tombstones) {
        this.tombstones = tombstones;
    }

    public OfferDetail create(
            String tenantId,
            Viewer viewer,
            String applicationId,
            String templateKey,
            TermsInput input,
            String coverNote) {
        requireCreate(viewer);
        var existing = offers.findByApplication(tenantId, applicationId);
        if (existing.isPresent() && !existing.get().status().closed()) return detail(existing.get());
        var application = applications.application(tenantId, applicationId);
        if (tombstones != null && tombstones.isErased(tenantId, application.candidateId()))
            throw new IllegalStateException("The candidate's data was erased; no offer is created");
        if (!"OFFER".equals(application.stage()))
            throw new IllegalStateException("Record a hire decision before creating an offer");
        var job = jobs.job(tenantId, application.jobId());
        var now = clock.instant();
        var today = today();
        var base = defaults(job, today);
        var terms = merge(base, input);
        CompensationPolicy.validate(terms, job.band(), today);
        var version = version(
                tenantId,
                templateKey,
                1,
                application.candidateName(),
                application.jobTitle(),
                terms,
                coverNote,
                viewer.displayName(),
                now);
        Offer offer;
        if (existing.isPresent()) {
            offer = existing.get();
            offer.reopen(viewer.subject(), job.band(), nextVersion(offer, version), now);
        } else {
            offer = Offer.create(
                    tenantId,
                    applicationId,
                    application.candidateId(),
                    application.candidateName(),
                    application.jobId(),
                    application.jobTitle(),
                    viewer.subject(),
                    job.band(),
                    version,
                    now);
        }
        offers.save(offer);
        audit.record(staff(tenantId, "OFFER_CREATED", viewer, offer)
                .detail("versionNo", offer.latest().versionNo())
                .build());
        return detail(offer);
    }

    public OfferDetail update(String tenantId, Viewer viewer, String offerId, TermsInput input, String coverNote) {
        requireCreate(viewer);
        var offer = require(tenantId, offerId);
        var job = jobs.job(tenantId, offer.jobId());
        var current = offer.latest();
        var terms = merge(current.terms(), input);
        var cover = coverNote == null ? current.coverNote() : coverNote;
        CompensationPolicy.validate(terms, job.band(), today());
        var now = clock.instant();
        var draft = version(
                tenantId,
                current.templateKey(),
                current.versionNo(),
                offer.candidateName(),
                offer.jobTitle(),
                terms,
                cover,
                viewer.displayName(),
                now);
        if (offer.revise(draft, job.band(), now)) offers.save(offer);
        return detail(offer);
    }

    public OfferDetail submit(String tenantId, Viewer viewer, String offerId) {
        requireCreate(viewer);
        var offer = require(tenantId, offerId);
        var terms = offer.latest().terms();
        if (terms.reportsTo() == null || terms.reportsTo().isBlank())
            throw new IllegalArgumentException("Say who the new hire reports to before submitting for approval");
        if (terms.location() == null || terms.location().isBlank())
            throw new IllegalArgumentException("Add the work location before submitting for approval");
        offer.submit(clock.instant());
        offers.save(offer);
        audit.record(staff(tenantId, "OFFER_SUBMITTED", viewer, offer)
                .detail("versionNo", offer.latest().versionNo())
                .build());
        return detail(offer);
    }

    public OfferDetail approve(String tenantId, Viewer viewer, String offerId, String stepId) {
        requireRead(viewer);
        var offer = require(tenantId, offerId);
        var stepRole = stepRole(offer, stepId);
        if (offer.approve(stepId, viewer, clock.instant())) {
            offers.save(offer);
            audit.record(staff(tenantId, "OFFER_APPROVED", viewer, offer)
                    .detail("stepRole", stepRole)
                    .build());
        }
        return detail(offer);
    }

    public OfferDetail reject(String tenantId, Viewer viewer, String offerId, String stepId, String reason) {
        requireRead(viewer);
        var offer = require(tenantId, offerId);
        var stepRole = stepRole(offer, stepId);
        offer.reject(stepId, viewer, reason, clock.instant());
        offers.save(offer);
        audit.record(staff(tenantId, "OFFER_REJECTED", viewer, offer)
                .detail("stepRole", stepRole)
                .build());
        return detail(offer);
    }

    public OfferDetail send(String tenantId, Viewer viewer, String offerId) {
        if (!viewer.canCreate()) throw new AccessDenied("Recruiter or HR access required to send offers");
        var offer = require(tenantId, offerId);
        var now = clock.instant();
        var expiry = LocalDate.parse(offer.latest().terms().expiryDate())
                .plusDays(1)
                .atStartOfDay(settings.zone())
                .toInstant();
        if (!expiry.isAfter(now))
            throw new IllegalArgumentException("The offer expiry date has passed; extend it first");
        var url = settings.portalUrl() + "/offer#token="
                + URLEncoder.encode(
                        tokens.issue(tenantId, offer.id(), expiry.plus(settings.tokenGrace())), StandardCharsets.UTF_8);
        if (offer.send(expiry, url, now)) {
            offers.save(offer);
            audit.record(staff(tenantId, "OFFER_SENT", viewer, offer)
                    .detail("versionNo", offer.sentVersion().versionNo())
                    .build());
        }
        return detail(offer);
    }

    public OfferDetail rescind(String tenantId, Viewer viewer, String offerId, String reason) {
        if (!viewer.canRescind()) throw new AccessDenied("HR access required to rescind offers");
        var offer = require(tenantId, offerId);
        var wasRescinded = offer.status() == Status.RESCINDED;
        offer.rescind(reason, clock.instant());
        offers.save(offer);
        if (!wasRescinded)
            audit.record(staff(tenantId, "OFFER_RESCINDED", viewer, offer).build());
        return detail(offer);
    }

    // ---- Templates and clauses ------------------------------------------------------------------

    public List<TemplateView> templates(String tenantId, Viewer viewer) {
        requireRead(viewer);
        seed(tenantId);
        return templates.templates(tenantId).stream()
                .map(OfferApplicationService::templateView)
                .toList();
    }

    public TemplateView saveTemplate(
            String tenantId, Viewer viewer, String key, String name, String body, List<String> clauseKeys) {
        requireTemplateEditor(viewer);
        seed(tenantId);
        var keys = clauseKeys == null ? List.<String>of() : clauseKeys;
        for (var clauseKey : keys)
            if (templates.clause(tenantId, clauseKey).isEmpty())
                throw new IllegalArgumentException("Unknown clause " + clauseKey);
        var now = clock.instant();
        var template = templates.template(tenantId, key).orElse(null);
        if (template == null) {
            if (!key.matches("[a-z0-9-]{2,40}"))
                throw new IllegalArgumentException("Use 2-40 lowercase letters, digits or hyphens");
            template = OfferTemplate.create(
                    tenantId,
                    key,
                    name == null || name.isBlank() ? key : name.trim(),
                    "IN",
                    "ALL",
                    body,
                    keys,
                    viewer.subject(),
                    now);
        } else {
            template.revise(name, body, keys, viewer.subject(), now);
        }
        var saved = templates.saveTemplate(template);
        audit.record(AuditRecord.of(tenantId, "OFFER_TEMPLATE_CHANGED")
                .user(viewer.subject(), viewer.displayName())
                .resource("OfferTemplate", key)
                .detail("templateVersion", saved.version())
                .build());
        return templateView(saved);
    }

    public List<ClauseView> clauses(String tenantId, Viewer viewer) {
        requireRead(viewer);
        seed(tenantId);
        return templates.clauses(tenantId).stream()
                .map(OfferApplicationService::clauseView)
                .toList();
    }

    public ClauseView saveClause(String tenantId, Viewer viewer, String key, String title, String body) {
        requireTemplateEditor(viewer);
        seed(tenantId);
        var now = clock.instant();
        var clause = templates.clause(tenantId, key).orElse(null);
        if (clause == null) {
            if (!key.matches("[a-z0-9-]{2,40}"))
                throw new IllegalArgumentException("Use 2-40 lowercase letters, digits or hyphens");
            clause =
                    OfferClause.create(tenantId, key, title == null || title.isBlank() ? key : title.trim(), body, now);
        } else {
            clause.revise(title, body, now);
        }
        var saved = templates.saveClause(clause);
        audit.record(AuditRecord.of(tenantId, "OFFER_TEMPLATE_CHANGED")
                .user(viewer.subject(), viewer.displayName())
                .resource("OfferClause", key)
                .build());
        return clauseView(saved);
    }

    // ---- Candidate (public) ---------------------------------------------------------------------

    public PublicOfferView publicView(String token) {
        var offer = fromToken(token);
        if (offer.status() == Status.SENT) {
            offer.markViewed(clock.instant());
            offers.save(offer);
        }
        return publicView(offer);
    }

    public PublicOfferView publicAccept(String token, String fullName) {
        var offer = fromToken(token);
        if (offer.accept(fullName, clock.instant())) {
            offers.save(offer);
            audit.record(candidate(offer, "OFFER_ACCEPTED")
                    .detail("versionNo", offer.sentVersion().versionNo())
                    .build());
        }
        return publicView(offer);
    }

    public PublicOfferView publicDecline(String token, String reason) {
        var offer = fromToken(token);
        if (offer.decline(reason, clock.instant())) {
            offers.save(offer);
            audit.record(candidate(offer, "OFFER_DECLINED")
                    .detail("versionNo", offer.sentVersion().versionNo())
                    .build());
        }
        return publicView(offer);
    }

    public PublicOfferView publicNegotiate(String token, String message) {
        var offer = fromToken(token);
        offer.negotiate(message, clock.instant());
        offers.save(offer);
        return publicView(offer);
    }

    /** Lets the portal's status page learn whether an offer exists, using the application receipt. */
    public ReceiptOfferView receiptStatus(String tenantId, String applicationId, String receipt) {
        applications.verifyReceipt(tenantId, applicationId, receipt);
        var offer = offers.findByApplication(tenantId, applicationId).orElse(null);
        if (offer == null || offer.sentVersion() == null) return new ReceiptOfferView("NONE", null);
        var expiry = offer.expiresAt() == null ? clock.instant() : offer.expiresAt();
        var url = settings.portalUrl() + "/offer#token="
                + URLEncoder.encode(
                        tokens.issue(tenantId, offer.id(), expiry.plus(settings.tokenGrace())), StandardCharsets.UTF_8);
        return new ReceiptOfferView(candidateStatus(offer), url);
    }

    // ---- Background -----------------------------------------------------------------------------

    public int sweep() {
        int expired = 0;
        for (var offer : offers.findExpired(clock.instant(), 100))
            if (offer.expire(clock.instant())) {
                offers.save(offer);
                expired++;
            }
        return expired;
    }

    /** The application was withdrawn or rejected: an unanswered offer is rescinded. */
    public void closeForApplication(String tenantId, String applicationId, String reason) {
        offers.findByApplication(tenantId, applicationId).ifPresent(offer -> {
            if (offer.status() == Status.ACCEPTED || offer.status().closed()) return;
            offer.rescind(reason, clock.instant());
            offers.save(offer);
            audit.record(about(AuditRecord.of(tenantId, "OFFER_RESCINDED").system(), offer)
                    .detail("cause", "APPLICATION_CLOSED")
                    .build());
        });
    }

    // ---- Internals ------------------------------------------------------------------------------

    private static AuditRecord.Builder about(AuditRecord.Builder builder, Offer offer) {
        return builder.resource("Offer", offer.id()).about(offer.candidateId(), offer.applicationId());
    }

    private static AuditRecord.Builder staff(String tenantId, String action, Viewer viewer, Offer offer) {
        return about(AuditRecord.of(tenantId, action).user(viewer.subject(), viewer.displayName()), offer);
    }

    private static AuditRecord.Builder candidate(Offer offer, String action) {
        return about(AuditRecord.of(offer.tenantId(), action).candidate(offer.candidateId()), offer);
    }

    private static String stepRole(Offer offer, String stepId) {
        return offer.approvalSteps().stream()
                .filter(step -> step.id().equals(stepId))
                .map(step -> step.role())
                .findFirst()
                .orElse("UNKNOWN");
    }

    private Offer fromToken(String token) {
        var claims = tokens.verify(token, clock.instant());
        var offer = require(claims.tenantId(), claims.offerId());
        if (offer.sentVersion() == null) throw new ResourceNotFoundException("This offer is not available");
        return offer;
    }

    private PublicOfferView publicView(Offer offer) {
        var sent = offer.sentVersion();
        var terms = sent.terms();
        return new PublicOfferView(
                offer.jobTitle(),
                settings.companyName(),
                offer.candidateName(),
                candidateStatus(offer),
                new PublicTerms(
                        terms.currency(),
                        terms.base(),
                        terms.variable(),
                        terms.bonus(),
                        terms.joiningDate(),
                        terms.location(),
                        terms.designation(),
                        terms.reportsTo(),
                        terms.expiryDate()),
                sent.coverNote(),
                sent.letterText(),
                offer.expiresAt() == null ? null : offer.expiresAt().toString(),
                offer.respondedAt() == null ? null : offer.respondedAt().toString(),
                offer.canRespond(clock.instant()),
                offer.revising());
    }

    private String candidateStatus(Offer offer) {
        if (offer.revising()) return "REVISING";
        if (offer.status().open() && !offer.canRespond(clock.instant())) return Status.EXPIRED.name();
        return offer.status().name();
    }

    private OfferVersion version(
            String tenantId,
            String templateKey,
            int versionNo,
            String candidateName,
            String jobTitle,
            Terms terms,
            String coverNote,
            String by,
            Instant now) {
        seed(tenantId);
        var key = templateKey == null || templateKey.isBlank() ? DefaultTemplates.STANDARD : templateKey;
        var template = templates
                .template(tenantId, key)
                .orElseThrow(() -> new IllegalArgumentException("Unknown offer template " + key));
        var clauses = template.clauseKeys().stream()
                .map(clauseKey -> templates.clause(tenantId, clauseKey))
                .flatMap(java.util.Optional::stream)
                .toList();
        var rendered = OfferRenderer.render(
                template, clauses, candidateName, jobTitle, settings.companyName(), terms, coverNote, today());
        return new OfferVersion(
                versionNo,
                template.key(),
                template.version(),
                terms,
                coverNote,
                rendered.text(),
                rendered.sha256(),
                by,
                now,
                false,
                false);
    }

    private static OfferVersion nextVersion(Offer offer, OfferVersion fresh) {
        var number = offer.latest().versionNo() + 1;
        return new OfferVersion(
                number,
                fresh.templateKey(),
                fresh.templateVersion(),
                fresh.terms(),
                fresh.coverNote(),
                fresh.letterText(),
                fresh.sha256(),
                fresh.createdBy(),
                fresh.createdAt(),
                false,
                false);
    }

    private Terms defaults(com.openhire.offer.service.port.OfferPorts.JobInfo job, LocalDate today) {
        var band = job.band();
        return new Terms(
                band == null ? "INR" : band.currency(),
                band == null ? BigDecimal.ONE : band.midpoint(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                today.plusDays(30).toString(),
                null,
                job.hiringManager() == null ? "" : job.hiringManager(),
                job.location() == null ? "" : job.location(),
                today.plusDays(settings.defaultExpiryDays()).toString(),
                job.title());
    }

    private static Terms merge(Terms base, TermsInput input) {
        if (input == null) return base;
        return new Terms(
                pick(input.currency(), base.currency()),
                input.base() != null ? input.base() : base.base(),
                input.variable() != null ? input.variable() : base.variable(),
                input.bonus() != null ? input.bonus() : base.bonus(),
                pick(input.joiningDate(), base.joiningDate()),
                input.joiningDateNote() != null ? input.joiningDateNote() : base.joiningDateNote(),
                input.reportsTo() != null ? input.reportsTo() : base.reportsTo(),
                input.location() != null ? input.location() : base.location(),
                pick(input.expiryDate(), base.expiryDate()),
                pick(input.designation(), base.designation()));
    }

    private static String pick(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    private void seed(String tenantId) {
        var now = clock.instant();
        for (var clause : DefaultTemplates.clauses(tenantId, now)) templates.seedClause(clause);
        templates.seedTemplate(DefaultTemplates.standard(tenantId, now));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), settings.zone());
    }

    private Offer require(String tenantId, String offerId) {
        return offers.find(tenantId, offerId)
                .orElseThrow(() -> new ResourceNotFoundException("Offer " + offerId + " was not found"));
    }

    private static void requireRead(Viewer viewer) {
        if (!viewer.canRead()) throw new AccessDenied("Recruiting access required");
    }

    private static void requireCreate(Viewer viewer) {
        if (!viewer.canCreate()) throw new AccessDenied("Recruiter or HR access required");
    }

    private static void requireTemplateEditor(Viewer viewer) {
        if (!viewer.canRescind()) throw new AccessDenied("HR access required to change templates");
    }

    private OfferSummary summary(Offer offer) {
        return new OfferSummary(
                offer.id(),
                offer.applicationId(),
                offer.candidateId(),
                offer.candidateName(),
                offer.jobId(),
                offer.jobTitle(),
                offer.status().name(),
                line(offer),
                offer.latest().versionNo());
    }

    private OfferDetail detail(Offer offer) {
        var latest = offer.latest();
        var terms = latest.terms();
        var band = offer.band();
        var steps = offer.approvalSteps().stream()
                .map(step -> new StepView(
                        step.id(),
                        step.label(),
                        step.role(),
                        step.status().name(),
                        step.decidedAt() == null ? null : step.decidedAt().toString(),
                        step.decidedName() != null ? step.decidedName() : step.decidedBy(),
                        step.comment()))
                .toList();
        var versions = offer.versions().stream()
                .map(version -> new VersionView(
                        version.versionNo(),
                        version.createdAt().toString(),
                        version.createdBy(),
                        version.sha256(),
                        version.sent()))
                .toList();
        return new OfferDetail(
                offer.id(),
                offer.applicationId(),
                offer.candidateId(),
                offer.candidateName(),
                offer.jobId(),
                offer.jobTitle(),
                offer.status().name(),
                line(offer),
                latest.versionNo(),
                steps,
                offer.approvalNote(),
                templateName(offer.tenantId(), latest.templateKey()),
                latest.coverNote(),
                new TermsView(
                        terms.currency(),
                        terms.base(),
                        terms.variable(),
                        terms.bonus(),
                        terms.joiningDate(),
                        terms.joiningDateNote(),
                        terms.reportsTo(),
                        terms.location(),
                        terms.expiryDate(),
                        CompensationPolicy.withinBand(terms, band),
                        terms.designation()),
                band == null ? null : new BandView(band.currency(), band.min(), band.max()),
                offer.expiresAt() == null ? null : offer.expiresAt().toString(),
                offer.sentAt() == null ? null : offer.sentAt().toString(),
                offer.respondedAt() == null ? null : offer.respondedAt().toString(),
                offer.candidateNote(),
                versions);
    }

    private String templateName(String tenantId, String key) {
        return templates.template(tenantId, key).map(OfferTemplate::name).orElse(key);
    }

    private String line(Offer offer) {
        var expiry = offer.expiresAt() == null
                ? ""
                : SHORT.format(offer.expiresAt().atZone(settings.zone()).minusSeconds(1));
        return switch (offer.status()) {
            case DRAFT -> offer.approvalNote() != null ? offer.approvalNote() : "Draft, not yet submitted";
            case PENDING_APPROVAL ->
                offer.currentStep() == null
                        ? "Pending approval"
                        : "Pending " + offer.currentStep().label() + " approval";
            case APPROVED -> "Approved, ready to send";
            case SENT -> "Sent, expires " + expiry;
            case VIEWED -> "Viewed by the candidate, expires " + expiry;
            case NEGOTIATING -> "Candidate asked for changes";
            case ACCEPTED -> "Accepted " + SHORT.format(offer.respondedAt().atZone(settings.zone()));
            case DECLINED -> offer.responseReason() == null ? "Declined" : "Declined: " + offer.responseReason();
            case EXPIRED -> "Expired " + expiry;
            case RESCINDED -> offer.responseReason() == null ? "Rescinded" : "Rescinded: " + offer.responseReason();
        };
    }

    private static TemplateView templateView(OfferTemplate template) {
        return new TemplateView(
                template.key(),
                template.name(),
                template.country(),
                template.level(),
                template.version(),
                template.body(),
                template.clauseKeys(),
                template.updatedAt().toString());
    }

    private static ClauseView clauseView(OfferClause clause) {
        return new ClauseView(
                clause.key(),
                clause.title(),
                clause.body(),
                clause.version(),
                clause.updatedAt().toString());
    }
}
