package com.openhire.talent.domain;

import com.openhire.talent.domain.TalentTypes.ApplicationRef;
import com.openhire.talent.domain.TalentTypes.Chunk;
import com.openhire.talent.domain.TalentTypes.Consent;
import com.openhire.talent.domain.TalentTypes.Evidence;
import com.openhire.talent.domain.TalentTypes.ProfileState;
import com.openhire.talent.domain.TalentTypes.StageStep;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Search projection of one candidate. Identity, applications and stage history exist for every candidate (they are
 * needed to exclude employees and to honour cooldowns); skills, resume excerpts and the vector are built only while
 * the candidate's talent-pool consent is GRANTED and are removed on revocation or expiry.
 */
@Document("talent_profiles")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_candidate", def = "{'tenantId': 1, 'candidateId': 1}", unique = true),
    @CompoundIndex(name = "tenant_pool", def = "{'tenantId': 1, 'consent': 1, 'state': 1, 'retentionUntil': 1}"),
    @CompoundIndex(name = "tenant_application", def = "{'tenantId': 1, 'applications.applicationId': 1}"),
    @CompoundIndex(name = "retention_due", def = "{'state': 1, 'retentionUntil': 1}")
})
public class TalentProfile {
    static final Set<String> INTERVIEW_STAGES = Set.of("INTERVIEWING", "DECISION_PENDING", "OFFER");
    private static final int MAX_STEPS = 200;
    private static final int MAX_EVIDENCE = 20;

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String candidateId;
    private String name;
    private Consent consent = Consent.UNKNOWN;
    private Instant consentUpdatedAt;
    private ProfileState state = ProfileState.ACTIVE;
    private boolean employee;
    private Instant lastActivityAt;
    private Instant retentionUntil;
    private String latestApplicationId;
    private Instant latestAppliedAt;
    private List<ApplicationRef> applications = new ArrayList<>();
    private List<StageStep> stageHistory = new ArrayList<>();
    private List<Evidence> historical = new ArrayList<>();

    private boolean contentBuilt;
    private boolean embedded;
    private String resumeId;
    private String headline;
    private List<String> skills = new ArrayList<>();
    private List<String> titles = new ArrayList<>();
    private List<String> locations = new ArrayList<>();
    private Double totalYears;
    private Boolean openToRelocate;
    private String summary;
    private List<Chunk> chunks = new ArrayList<>();
    private Instant contentBuiltAt;

    protected TalentProfile() {}

    public static String idFor(String tenantId, String candidateId) {
        return tenantId + ":" + candidateId;
    }

    public static TalentProfile create(String tenantId, String candidateId, String name, Instant now, int months) {
        var profile = new TalentProfile();
        profile.id = idFor(tenantId, candidateId);
        profile.tenantId = tenantId;
        profile.candidateId = candidateId;
        profile.name = name == null ? "" : name;
        profile.lastActivityAt = now;
        profile.retentionUntil = retentionFrom(now, months);
        return profile;
    }

    static Instant retentionFrom(Instant lastActivity, int months) {
        return lastActivity.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
    }

    // ---- Projection from events ---------------------------------------------------------------------

    /** Records new activity: retention restarts from it and an expired profile becomes active again. */
    public void recordActivity(Instant at, int months) {
        if (lastActivityAt == null || at.isAfter(lastActivityAt)) {
            lastActivityAt = at;
            retentionUntil = retentionFrom(at, months);
        }
        if (state == ProfileState.EXPIRED) {
            state = ProfileState.ACTIVE;
            contentBuilt = false;
        }
    }

    public void rename(String value) {
        if (value != null && !value.isBlank()) name = value;
    }

    public void recordApplication(ApplicationRef ref) {
        var existing = application(ref.applicationId());
        applications.removeIf(value -> value.applicationId().equals(ref.applicationId()));
        applications.add(
                existing == null || ref.assessmentScore() != null ? ref : ref.withScore(existing.assessmentScore()));
        applications.sort(
                Comparator.comparing(ApplicationRef::appliedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        var latest = applications.getLast();
        latestApplicationId = latest.applicationId();
        latestAppliedAt = latest.appliedAt();
    }

    /** Returns false when the same event was already applied (redelivery). */
    public boolean recordStep(StageStep step) {
        if (stageHistory.stream().anyMatch(value -> value.eventId().equals(step.eventId()))) return false;
        stageHistory.add(step);
        if (stageHistory.size() > MAX_STEPS) stageHistory.removeFirst();
        return true;
    }

    public void recordAssessment(String applicationId, Integer score, List<Evidence> notes) {
        var ref = application(applicationId);
        if (ref != null && score != null) recordApplication(ref.withScore(score));
        historical.removeIf(value -> applicationId.equals(value.applicationId()));
        historical.addAll(notes);
        while (historical.size() > MAX_EVIDENCE) historical.removeFirst();
    }

    public void markEmployee() {
        employee = true;
    }

    // ---- Consent, content and retention -------------------------------------------------------------

    public void grant(Instant now) {
        consent = Consent.GRANTED;
        consentUpdatedAt = now;
    }

    public void revoke(Instant now) {
        consent = Consent.REVOKED;
        consentUpdatedAt = now;
        clearContent();
    }

    public void expire() {
        state = ProfileState.EXPIRED;
        clearContent();
    }

    public void applyContent(
            String resumeId,
            String headline,
            List<String> skills,
            List<String> titles,
            List<String> locations,
            Double totalYears,
            String summary,
            List<Chunk> chunks,
            Instant now) {
        this.resumeId = resumeId;
        this.headline = headline;
        this.skills = new ArrayList<>(skills);
        this.titles = new ArrayList<>(titles);
        this.locations = new ArrayList<>(locations);
        this.totalYears = totalYears;
        this.summary = summary;
        this.chunks = new ArrayList<>(chunks);
        this.contentBuilt = true;
        this.embedded = false;
        this.contentBuiltAt = now;
    }

    public void markEmbedded(boolean value) {
        embedded = value;
    }

    public void markContentStale() {
        contentBuilt = false;
        embedded = false;
    }

    private void clearContent() {
        contentBuilt = false;
        embedded = false;
        resumeId = null;
        headline = null;
        skills = new ArrayList<>();
        titles = new ArrayList<>();
        locations = new ArrayList<>();
        totalYears = null;
        summary = null;
        chunks = new ArrayList<>();
        historical = new ArrayList<>();
        contentBuiltAt = null;
    }

    /** Absorbs another profile of the same person after a candidate merge; the stricter consent wins. */
    public void absorb(TalentProfile other, int months) {
        for (var ref : other.applications) if (application(ref.applicationId()) == null) recordApplication(ref);
        for (var step : other.stageHistory) recordStep(step);
        if (other.consent == Consent.REVOKED || consent == Consent.REVOKED) consent = Consent.REVOKED;
        else if (consent == Consent.UNKNOWN && other.consent == Consent.GRANTED) {
            consent = Consent.GRANTED;
            consentUpdatedAt = other.consentUpdatedAt;
        }
        employee = employee || other.employee;
        if (other.lastActivityAt != null) recordActivity(other.lastActivityAt, months);
        markContentStale();
        if (consent != Consent.GRANTED) clearContent();
    }

    // ---- Queries ------------------------------------------------------------------------------------

    /** Consent granted, retention valid, content built and (unless internal mobility is on) not an employee. */
    public boolean searchable(Instant now, boolean includeEmployees) {
        return consent == Consent.GRANTED
                && state == ProfileState.ACTIVE
                && contentBuilt
                && retentionUntil != null
                && retentionUntil.isAfter(now)
                && (includeEmployees || !employee);
    }

    public ApplicationRef application(String applicationId) {
        return applications.stream()
                .filter(value -> value.applicationId().equals(applicationId))
                .findFirst()
                .orElse(null);
    }

    public boolean appliedTo(String jobId) {
        return jobId != null && applications.stream().anyMatch(value -> jobId.equals(value.jobId()));
    }

    public boolean reachedInterview() {
        return stageHistory.stream().anyMatch(step -> INTERVIEW_STAGES.contains(step.to()))
                || applications.stream().anyMatch(ref -> INTERVIEW_STAGES.contains(ref.stage()));
    }

    public boolean everShortlisted() {
        return reachedInterview()
                || stageHistory.stream().anyMatch(step -> "SHORTLISTED".equals(step.to()))
                || applications.stream().anyMatch(ref -> "SHORTLISTED".equals(ref.stage()));
    }

    public boolean hired() {
        return applications.stream().anyMatch(ref -> "HIRED".equals(ref.stage()))
                || stageHistory.stream().anyMatch(step -> "HIRED".equals(step.to()));
    }

    /** Reached interviews, was not hired, and has no application still moving through interviews. */
    public boolean silverMedalist() {
        return reachedInterview()
                && !hired()
                && applications.stream().noneMatch(ref -> INTERVIEW_STAGES.contains(ref.stage()));
    }

    public boolean interviewedWithin(Instant since) {
        return stageHistory.stream()
                .anyMatch(step -> "INTERVIEWING".equals(step.to()) && !step.at().isBefore(since));
    }

    public boolean reachedStage(String stage) {
        return applications.stream().anyMatch(ref -> stage.equals(ref.stage()))
                || stageHistory.stream().anyMatch(step -> stage.equals(step.to()));
    }

    /** The most recent held role, else the headline, else the last role applied for. */
    public String displayTitle() {
        if (!titles.isEmpty()) return titles.getFirst();
        if (headline != null && !headline.isBlank()) return headline;
        return applications.isEmpty() ? "" : applications.getLast().jobTitle();
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String candidateId() {
        return candidateId;
    }

    public String name() {
        return name;
    }

    public Consent consent() {
        return consent;
    }

    public Instant consentUpdatedAt() {
        return consentUpdatedAt;
    }

    public ProfileState state() {
        return state;
    }

    public boolean employee() {
        return employee;
    }

    public Instant lastActivityAt() {
        return lastActivityAt;
    }

    public Instant retentionUntil() {
        return retentionUntil;
    }

    public String latestApplicationId() {
        return latestApplicationId;
    }

    public List<ApplicationRef> applications() {
        return List.copyOf(applications);
    }

    public List<StageStep> stageHistory() {
        return List.copyOf(stageHistory);
    }

    public List<Evidence> historical() {
        return List.copyOf(historical);
    }

    public boolean contentBuilt() {
        return contentBuilt;
    }

    public boolean embedded() {
        return embedded;
    }

    public String resumeId() {
        return resumeId;
    }

    public String headline() {
        return headline;
    }

    public List<String> skills() {
        return List.copyOf(skills);
    }

    public List<String> titles() {
        return List.copyOf(titles);
    }

    public List<String> locations() {
        return List.copyOf(locations);
    }

    public Double totalYears() {
        return totalYears;
    }

    public Boolean openToRelocate() {
        return openToRelocate;
    }

    public String summary() {
        return summary;
    }

    public List<Chunk> chunks() {
        return List.copyOf(chunks);
    }
}
