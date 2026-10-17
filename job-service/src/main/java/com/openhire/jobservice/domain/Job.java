package com.openhire.jobservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("jobs")
@CompoundIndex(name = "tenant_updated", def = "{'tenantId': 1, 'updatedAt': -1}")
public class Job {
    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String code;
    private String title;
    private String team;
    private String level;
    private String location;
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String currency;
    private JobStatus status;
    private int currentVersion;
    private int approvedVersion;
    private List<JobVersion> versions;
    private List<HiringTeamMember> hiringTeam;
    private List<Activity> activity;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected Job() {}

    public static Job create(String tenantId, JobContent content, JobStatus requestedStatus, String actor) {
        var now = Instant.now();
        var value = new Job();
        value.id = UUID.randomUUID().toString();
        value.tenantId = tenantId;
        value.code = "OH-" + now.toString().substring(0, 4) + "-"
                + value.id.substring(0, 8).toUpperCase();
        value.status = requestedStatus == JobStatus.PENDING_APPROVAL ? JobStatus.PENDING_APPROVAL : JobStatus.DRAFT;
        value.currentVersion = 1;
        value.versions = new ArrayList<>();
        value.hiringTeam = new ArrayList<>();
        value.activity = new ArrayList<>();
        value.apply(content);
        value.versions.add(JobVersion.from(1, content, now));
        value.activity.add(new Activity(UUID.randomUUID().toString(), now, actor, "Created the job"));
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public static Job create(
            String tenantId,
            String title,
            String team,
            String level,
            String location,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            JobStatus status) {
        return create(
                tenantId,
                new JobContent(
                        title,
                        team,
                        level,
                        location,
                        salaryMin,
                        salaryMax,
                        currency,
                        mustHaveSkills,
                        knockoutQuestions,
                        rubric,
                        description,
                        List.of()),
                status,
                "system");
    }

    public void edit(int expectedVersion, JobContent content, String actor) {
        requireVersion(expectedVersion);
        requireStatus(JobStatus.DRAFT);
        currentVersion++;
        apply(content);
        versions.add(JobVersion.from(currentVersion, content, Instant.now()));
        approvedVersion = 0;
        record(actor, "Created version " + currentVersion);
    }

    public void updateHiringTeam(int expectedVersion, List<HiringTeamMember> members, String actor) {
        requireVersion(expectedVersion);
        hiringTeam = new ArrayList<>(members == null ? List.of() : members);
        record(actor, "Updated the hiring team");
    }

    public void updateInterviewPlan(int expectedVersion, List<InterviewRound> rounds, String actor) {
        requireVersion(expectedVersion);
        var content = content();
        edit(
                expectedVersion,
                new JobContent(
                        content.title(),
                        content.team(),
                        content.level(),
                        content.location(),
                        content.salaryMin(),
                        content.salaryMax(),
                        content.currency(),
                        content.mustHaveSkills(),
                        content.knockoutQuestions(),
                        content.rubric(),
                        content.description(),
                        rounds),
                actor);
    }

    public void submit(String actor) {
        requireStatus(JobStatus.DRAFT);
        status = JobStatus.PENDING_APPROVAL;
        record(actor, "Submitted version " + currentVersion + " for approval");
    }

    public void markApproved(String actor) {
        if (status == JobStatus.PENDING_APPROVAL && approvedVersion == currentVersion) return;
        requireStatus(JobStatus.PENDING_APPROVAL);
        approvedVersion = currentVersion;
        record(actor, "Approved version " + currentVersion);
    }

    public void requestChanges(String actor, String reason) {
        requireStatus(JobStatus.PENDING_APPROVAL);
        status = JobStatus.DRAFT;
        approvedVersion = 0;
        record(actor, "Changes requested: " + reason);
    }

    public void publish(String actor) {
        requireStatus(JobStatus.PENDING_APPROVAL);
        if (approvedVersion != currentVersion) throw new IllegalStateException("Current job version is not approved");
        status = JobStatus.OPEN;
        publishedAt = Instant.now();
        record(actor, "Published version " + currentVersion);
    }

    public void pause(String actor) {
        requireStatus(JobStatus.OPEN);
        status = JobStatus.PAUSED;
        record(actor, "Paused the job");
    }

    public void close(String actor) {
        if (status != JobStatus.OPEN && status != JobStatus.PAUSED) {
            throw new IllegalStateException("Only an open or paused job can be closed");
        }
        status = JobStatus.CLOSED;
        record(actor, "Closed the job");
    }

    public void reopen(String actor) {
        if (status != JobStatus.PAUSED && status != JobStatus.CLOSED) {
            throw new IllegalStateException("Only a paused or closed job can be reopened");
        }
        status = JobStatus.OPEN;
        record(actor, "Reopened the job");
    }

    private void apply(JobContent content) {
        title = content.title();
        team = content.team();
        level = content.level();
        location = content.location();
        salaryMin = content.salaryMin();
        salaryMax = content.salaryMax();
        currency = content.currency();
    }

    private JobContent content() {
        var version = currentSnapshot();
        return new JobContent(
                title,
                team,
                level,
                location,
                salaryMin,
                salaryMax,
                currency,
                version.mustHaveSkills(),
                version.knockoutQuestions(),
                version.rubric(),
                version.description(),
                version.interviewPlan());
    }

    private void requireVersion(int expectedVersion) {
        if (currentVersion != expectedVersion) {
            throw new IllegalStateException("Job was changed; expected version " + expectedVersion
                    + " but current version is " + currentVersion);
        }
    }

    private void requireStatus(JobStatus expected) {
        if (status != expected) throw new IllegalStateException("Job must be " + expected + " but is " + status);
    }

    private void record(String actor, String text) {
        var now = Instant.now();
        activity.add(new Activity(UUID.randomUUID().toString(), now, actor == null ? "system" : actor, text));
        updatedAt = now;
    }

    public JobVersion currentSnapshot() {
        return versions.stream()
                .filter(value -> value.version() == currentVersion)
                .findFirst()
                .orElseThrow();
    }

    public String id() {
        return id;
    }

    public Long revision() {
        return revision;
    }

    public String tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public String team() {
        return team;
    }

    public String level() {
        return level;
    }

    public String location() {
        return location;
    }

    public BigDecimal salaryMin() {
        return salaryMin;
    }

    public BigDecimal salaryMax() {
        return salaryMax;
    }

    public String currency() {
        return currency;
    }

    public List<String> mustHaveSkills() {
        return currentSnapshot().mustHaveSkills();
    }

    public List<KnockoutQuestion> knockoutQuestions() {
        return currentSnapshot().knockoutQuestions();
    }

    public List<RubricCriterion> rubric() {
        return currentSnapshot().rubric();
    }

    public String description() {
        return currentSnapshot().description();
    }

    public JobStatus status() {
        return status;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public int approvedVersion() {
        return approvedVersion;
    }

    public List<JobVersion> versions() {
        return List.copyOf(versions);
    }

    public List<HiringTeamMember> hiringTeam() {
        return List.copyOf(hiringTeam);
    }

    public List<Activity> activity() {
        return List.copyOf(activity);
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public record JobContent(
            String title,
            String team,
            String level,
            String location,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            String currency,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            String description,
            List<InterviewRound> interviewPlan) {
        public JobContent {
            mustHaveSkills = List.copyOf(mustHaveSkills == null ? List.of() : mustHaveSkills);
            knockoutQuestions = List.copyOf(knockoutQuestions == null ? List.of() : knockoutQuestions);
            rubric = List.copyOf(rubric == null ? List.of() : rubric);
            interviewPlan = List.copyOf(interviewPlan == null ? List.of() : interviewPlan);
        }
    }

    public record JobVersion(
            int version,
            String description,
            List<String> mustHaveSkills,
            List<KnockoutQuestion> knockoutQuestions,
            List<RubricCriterion> rubric,
            List<InterviewRound> interviewPlan,
            Instant createdAt) {
        static JobVersion from(int version, JobContent content, Instant at) {
            return new JobVersion(
                    version,
                    content.description(),
                    content.mustHaveSkills(),
                    content.knockoutQuestions(),
                    content.rubric(),
                    content.interviewPlan(),
                    at);
        }
    }

    public record KnockoutQuestion(String id, String question, String requiredAnswer) {}

    public record RubricCriterion(String id, String label, int weight) {}

    public record InterviewRound(String id, String name, int durationMin, List<String> interviewerNames) {}

    public record HiringTeamMember(String userId, String name, String role) {}

    public record Activity(String id, Instant at, String actor, String text) {}
}
