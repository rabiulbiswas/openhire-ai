package com.openhire.analytics.application;

import com.openhire.analytics.application.port.AnalyticsPorts.AiDayFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.ApplicationDirectory;
import com.openhire.analytics.application.port.AnalyticsPorts.ApplicationFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.AssessmentFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.DecisionFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.JobTitles;
import com.openhire.analytics.application.port.AnalyticsPorts.LatencyFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.OfferFacts;
import com.openhire.analytics.application.port.AnalyticsPorts.ProcessedEvents;
import com.openhire.analytics.domain.ApplicationFact;
import com.openhire.analytics.domain.Envelope;
import com.openhire.analytics.domain.Facts;
import com.openhire.analytics.domain.Facts.AiSample;
import com.openhire.analytics.domain.Facts.AssessmentFact;
import com.openhire.analytics.domain.Facts.DecisionFact;
import com.openhire.analytics.domain.Facts.DecisionKind;
import com.openhire.analytics.domain.Facts.Edge;
import com.openhire.analytics.domain.Facts.LatencyKind;
import com.openhire.analytics.domain.Facts.OfferFact;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folds domain events into the materialized views. Every handler claims the event id first, so a redelivered or
 * replayed event changes nothing; if applying fails the claim is released and the failure propagates for a retry.
 * Unknown event types and events with missing optional fields are skipped with a log line, never failed.
 */
public class ProjectionService {
    private static final Logger LOG = LoggerFactory.getLogger(ProjectionService.class);
    private static final Set<String> NOT_ERRORS = Set.of("OK", "REPAIRED", "CACHED");

    private final ProcessedEvents processed;
    private final ApplicationFacts applications;
    private final AssessmentFacts assessments;
    private final DecisionFacts decisions;
    private final OfferFacts offers;
    private final AiDayFacts aiDays;
    private final LatencyFacts latencies;
    private final JobTitles titles;
    private final ApplicationDirectory directory;
    private final Clock clock;

    public ProjectionService(
            ProcessedEvents processed,
            ApplicationFacts applications,
            AssessmentFacts assessments,
            DecisionFacts decisions,
            OfferFacts offers,
            AiDayFacts aiDays,
            LatencyFacts latencies,
            JobTitles titles,
            ApplicationDirectory directory,
            Clock clock) {
        this.processed = processed;
        this.applications = applications;
        this.assessments = assessments;
        this.decisions = decisions;
        this.offers = offers;
        this.aiDays = aiDays;
        this.latencies = latencies;
        this.titles = titles;
        this.directory = directory;
        this.clock = clock;
    }

    public void onApplicationEvent(Envelope event) {
        once("application", event, () -> {
            var tenant = event.tenantId();
            var id = event.aggregateId();
            if (tenant.isBlank() || id.isBlank()) return;
            var at = at(event);
            var fact = applications.find(tenant, id).orElse(ApplicationFact.empty(tenant, id));
            switch (event.eventType()) {
                case "ApplicationSubmitted" -> {
                    var jobId = event.text("jobId");
                    fact = fact.withJob(jobId, jobTitle(tenant, jobId, id), event.text("source"))
                            .withSubmitted(at);
                    latencies.mark(tenant, LatencyKind.RESUME_PROCESSING, id, Edge.START, at);
                }
                case "ApplicationScreened" -> {
                    fact = backfilled(fact).withScreened(at);
                    latencies.mark(tenant, LatencyKind.AI_ASSESSMENT, id, Edge.START, at);
                }
                case "ApplicationStageChanged" -> {
                    var to = event.text("to");
                    if (to.isBlank()) return;
                    fact = backfilled(fact).withStage(to, at);
                    recordHumanDecision(event, fact, to, at);
                }
                case "ApplicationNeedsAttention" -> fact = backfilled(fact).withStage("NEEDS_ATTENTION", at);
                case "ApplicationDecisionUndone" -> {
                    fact = fact.withDecisionUndone();
                    decisions.markLatestUndone(tenant, id);
                }
                case "AssessmentOverridden" -> {
                    recordOverride(event, backfilled(fact), at);
                    return;
                }
                default -> {
                    return;
                }
            }
            applications.save(fact);
        });
    }

    public void onAssessmentEvent(Envelope event) {
        once("assessment", event, () -> {
            if (!"assessment.completed.v1".equals(event.eventType())) return;
            var applicationId = event.text("applicationId");
            if (event.tenantId().isBlank() || applicationId.isBlank()) {
                LOG.warn("Assessment event {} has no tenant or application; skipped", event.eventId());
                return;
            }
            var at = at(event);
            var assessmentId = event.text("assessmentId").isBlank() ? event.aggregateId() : event.text("assessmentId");
            var status = event.text("status").isBlank() ? "UNKNOWN" : event.text("status");
            var screenedAt = applications
                    .find(event.tenantId(), applicationId)
                    .map(ApplicationFact::screenedAt)
                    .orElse(null);
            assessments.save(new AssessmentFact(
                    event.tenantId(),
                    assessmentId.isBlank() ? applicationId + "@" + at : assessmentId,
                    applicationId,
                    event.text("jobId"),
                    (int) Math.round(event.number("score")),
                    event.text("confidence"),
                    String.valueOf(event.map("provenance").getOrDefault("model", "")),
                    status,
                    at,
                    screenedAt));
            if ("COMPLETE".equals(status))
                latencies.mark(event.tenantId(), LatencyKind.AI_ASSESSMENT, applicationId, Edge.END, at);
        });
    }

    public void onOfferEvent(Envelope event) {
        once("offer", event, () -> {
            if (!event.eventType().startsWith("offer.") || event.tenantId().isBlank()) return;
            var offerId = event.text("offerId");
            if (offerId.isBlank()) return;
            if (!event.text("jobId").isBlank() && !event.text("jobTitle").isBlank())
                titles.put(event.tenantId(), event.text("jobId"), event.text("jobTitle"));
            var fact = offers.find(event.tenantId(), offerId).orElse(OfferFact.empty(event.tenantId(), offerId));
            offers.save(fact.with(event.eventType(), at(event), event.text("applicationId"), event.text("jobId")));
        });
    }

    public void onResumeEvent(Envelope event) {
        once("resume", event, () -> {
            if (!"ResumeProcessed".equals(event.eventType())) return;
            var applicationId = event.text("applicationId");
            if (event.tenantId().isBlank() || applicationId.isBlank()) return;
            latencies.mark(event.tenantId(), LatencyKind.RESUME_PROCESSING, applicationId, Edge.END, at(event));
        });
    }

    /** {@code communication.message.requested.v1} on the commands topic starts the notification hand-off clock. */
    public void onCommunicationCommand(Envelope event) {
        once("communication-commands", event, () -> {
            if (!"communication.message.requested.v1".equals(event.eventType())) return;
            var key = messageKey(event);
            if (event.tenantId().isBlank() || key.isBlank()) return;
            latencies.mark(event.tenantId(), LatencyKind.NOTIFICATION_HANDOFF, key, Edge.START, at(event));
        });
    }

    public void onDeliveryEvent(Envelope event) {
        once("communication-delivery", event, () -> {
            if (!"communication.delivery.updated.v1".equals(event.eventType())) return;
            var key = messageKey(event);
            if (event.tenantId().isBlank() || key.isBlank()) return;
            latencies.mark(event.tenantId(), LatencyKind.NOTIFICATION_HANDOFF, key, Edge.END, at(event));
        });
    }

    public void onAiEvent(Envelope event) {
        once("ai", event, () -> {
            if (!"ai.request.completed.v1".equals(event.eventType())
                    || event.tenantId().isBlank()) return;
            var created = parse(event.text("createdAt"));
            var day = (created == null ? at(event) : created)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate();
            var outcome = event.text("outcome");
            aiDays.add(new AiSample(
                    event.tenantId(),
                    day,
                    event.text("task").isBlank() ? "unknown" : event.text("task"),
                    event.text("model").isBlank() ? "unknown" : event.text("model"),
                    !outcome.isBlank() && !NOT_ERRORS.contains(outcome),
                    (long) event.number("promptTokens"),
                    (long) event.number("completionTokens"),
                    event.number("costInr"),
                    (long) event.number("latencyMs")));
        });
    }

    private void recordHumanDecision(Envelope event, ApplicationFact fact, String to, Instant at) {
        var actor = event.text("actorId");
        if (!human(actor) || !(to.equals("SHORTLISTED") || to.equals("REJECTED"))) return;
        decisions.add(new DecisionFact(
                event.tenantId(),
                event.eventId().isBlank() ? event.aggregateId() + "@" + at + ":" + to : event.eventId(),
                fact.applicationId(),
                fact.jobId(),
                to.equals("SHORTLISTED") ? DecisionKind.SHORTLIST : DecisionKind.REJECT,
                "USER",
                actor,
                at,
                event.text("from"),
                to,
                null,
                null,
                false));
    }

    private void recordOverride(Envelope event, ApplicationFact fact, Instant at) {
        var actor = event.text("actorId");
        if (!human(actor)) return;
        decisions.add(new DecisionFact(
                event.tenantId(),
                event.eventId().isBlank() ? event.aggregateId() + "@" + at + ":override" : event.eventId(),
                fact.applicationId(),
                fact.jobId(),
                DecisionKind.OVERRIDE,
                "USER",
                actor,
                at,
                null,
                null,
                Facts.overrideReason(event.text("reasonCode")),
                event.payload().get("score") instanceof Number score ? score.intValue() : null,
                false));
    }

    /** Service accounts and the system move applications too, but they are not human decisions. */
    static boolean human(String actor) {
        if (actor == null || actor.isBlank()) return false;
        var lower = actor.toLowerCase(java.util.Locale.ROOT);
        return !(lower.equals("system") || lower.startsWith("system:") || lower.endsWith("-service"));
    }

    private String jobTitle(String tenant, String jobId, String applicationId) {
        if (jobId.isBlank()) return null;
        var known = titles.find(tenant, jobId);
        if (known.isPresent()) return known.get();
        try {
            var title = directory.lookup(tenant, applicationId).map(info -> info.jobTitle());
            title.filter(value -> !value.isBlank()).ifPresent(value -> titles.put(tenant, jobId, value));
            return title.orElse(null);
        } catch (RuntimeException unavailable) {
            LOG.warn("Job title for {} not resolved: {}", jobId, unavailable.toString());
            return null;
        }
    }

    /** An application first seen after its submission (for example before analytics existed) is completed once. */
    private ApplicationFact backfilled(ApplicationFact fact) {
        if (fact.jobId() != null && fact.submittedAt() != null) return fact;
        try {
            var info = directory.lookup(fact.tenantId(), fact.applicationId());
            if (info.isEmpty()) return fact;
            var found = info.get();
            var result = fact.withJob(found.jobId(), found.jobTitle(), found.source());
            if (!found.jobId().isBlank() && !found.jobTitle().isBlank())
                titles.put(fact.tenantId(), found.jobId(), found.jobTitle());
            return fact.submittedAt() == null && found.appliedAt() != null
                    ? result.withSubmitted(found.appliedAt())
                    : result;
        } catch (RuntimeException unavailable) {
            LOG.warn("Application {} not backfilled: {}", fact.applicationId(), unavailable.toString());
            return fact;
        }
    }

    private static String messageKey(Envelope event) {
        return event.text("messageId").isBlank() ? event.aggregateId() : event.text("messageId");
    }

    private Instant at(Envelope event) {
        return event.occurredAt() == null ? clock.instant() : event.occurredAt();
    }

    private static Instant parse(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (java.time.format.DateTimeParseException invalid) {
            return null;
        }
    }

    private void once(String consumer, Envelope event, Runnable apply) {
        if (event.eventId().isBlank()) {
            LOG.warn("{} event {} has no eventId; skipped", consumer, event.eventType());
            return;
        }
        if (!processed.claim(consumer, event.eventId(), clock.instant())) return;
        try {
            apply.run();
        } catch (RuntimeException failure) {
            processed.release(consumer, event.eventId());
            throw failure;
        }
    }
}
