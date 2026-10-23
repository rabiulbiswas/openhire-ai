package com.openhire.compliance.service;

import com.openhire.compliance.domain.Fairness;
import com.openhire.compliance.domain.Stages;
import com.openhire.compliance.domain.Times;
import com.openhire.compliance.service.port.CompliancePorts.ActivityStore;
import com.openhire.compliance.service.port.CompliancePorts.Events;
import com.openhire.compliance.service.port.CompliancePorts.FairnessStore;
import com.openhire.compliance.service.port.CompliancePorts.SelfIdentificationStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adverse-impact reports over self-identified applicants. Only aggregates ever leave this class. */
public class FairnessService {
    private final ActivityStore activity;
    private final SelfIdentificationStore selfIds;
    private final SelfIdentificationService selfIdentification;
    private final FairnessStore snapshots;
    private final Events events;
    private final ComplianceSettings settings;
    private final Clock clock;

    public FairnessService(
            ActivityStore activity,
            SelfIdentificationStore selfIds,
            SelfIdentificationService selfIdentification,
            FairnessStore snapshots,
            Events events,
            ComplianceSettings settings,
            Clock clock) {
        this.activity = activity;
        this.selfIds = selfIds;
        this.selfIdentification = selfIdentification;
        this.snapshots = snapshots;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    public Map<String, Object> report(String tenantId, String jobId, String dimension) {
        if (!Fairness.DIMENSIONS.contains(dimension))
            throw new IllegalArgumentException("dimension must be one of " + Fairness.DIMENSIONS);
        var job = jobId == null || jobId.isBlank() ? null : jobId;
        var docs = job == null ? activity.all(tenantId) : activity.byJob(tenantId, job);
        var ids = docs.stream().map(d -> Maps.text(d, "applicationId")).toList();
        var encrypted = selfIds.ciphertexts(tenantId, ids);
        var applicants = new ArrayList<Fairness.Applicant>();
        for (var doc : docs) {
            var applicationId = Maps.text(doc, "applicationId");
            var cipher = encrypted.get(applicationId);
            if (cipher == null) continue;
            var group = Maps.text(selfIdentification.decrypt(tenantId, applicationId, cipher), dimension);
            if (group == null || group.equals("PREFER_NOT_TO_SAY")) continue;
            applicants.add(new Fairness.Applicant(group, furthest(doc)));
        }
        var transitions = Fairness.analyse(applicants, settings.minGroupSize(), settings.adverseImpactThreshold());
        int flags = 0;
        for (var t : transitions)
            flags +=
                    (int) t.groups().stream().filter(Fairness.GroupResult::flag).count();
        var report = new LinkedHashMap<String, Object>();
        report.put("jobId", job);
        report.put("dimension", dimension);
        report.put("generatedAt", Times.format(clock.instant()));
        report.put("minGroupSize", settings.minGroupSize());
        report.put("adverseImpactThreshold", settings.adverseImpactThreshold());
        report.put("selfIdentifiedApplicants", applicants.size());
        report.put(
                "transitions",
                transitions.stream().map(Fairness.TransitionResult::view).toList());
        report.put("flags", flags);
        return report;
    }

    static int furthest(Map<String, Object> doc) {
        int best = Stages.rank("APPLIED");
        for (var stage : Maps.list(doc.get("stages"))) best = Math.max(best, Stages.rank(Maps.text(stage, "stage")));
        best = Math.max(best, Stages.rank(Maps.text(doc, "stage")));
        if (Boolean.TRUE.equals(doc.get("hired"))) best = Stages.rank("HIRED");
        return best;
    }

    /** Flagged groups across every dimension, for the overview. */
    public int flagCount(String tenantId) {
        int total = 0;
        for (var dimension : Fairness.DIMENSIONS)
            total += ((Number) report(tenantId, null, dimension).get("flags")).intValue();
        return total;
    }

    /** The weekly job: refreshes snapshots and raises an alert for each group newly below the threshold. */
    public int refreshAll() {
        int alerts = 0;
        for (var tenant : activity.tenants()) alerts += refresh(tenant);
        return alerts;
    }

    @SuppressWarnings("unchecked")
    int refresh(String tenantId) {
        var jobs = new LinkedHashSet<String>();
        jobs.add(null);
        activity.all(tenantId).stream()
                .map(d -> Maps.text(d, "jobId"))
                .filter(j -> j != null)
                .forEach(jobs::add);
        int alerts = 0;
        for (var job : jobs)
            for (var dimension : Fairness.DIMENSIONS) {
                var report = report(tenantId, job, dimension);
                var current = flagKeys(report);
                Set<String> previous = new HashSet<>(snapshots
                        .latest(tenantId, job == null ? "ALL" : job, dimension)
                        .map(s -> (List<String>) s.get("flagKeys"))
                        .orElse(List.of()));
                for (var transition : Maps.list(report.get("transitions")))
                    for (var group : Maps.list(transition.get("groups"))) {
                        var key = key(transition, group);
                        if (!Boolean.TRUE.equals(group.get("flag")) || previous.contains(key)) continue;
                        events.publish(
                                tenantId,
                                "FairnessReport",
                                (job == null ? "ALL" : job) + ":" + dimension,
                                "compliance.fairness.alert.v1",
                                Maps.of(
                                        "jobId", job,
                                        "dimension", dimension,
                                        "from", Maps.text(transition, "from"),
                                        "to", Maps.text(transition, "to"),
                                        "group", Maps.text(group, "group"),
                                        "n", group.get("n"),
                                        "adverseImpactRatio", group.get("adverseImpactRatio")));
                        alerts++;
                    }
                snapshots.save(Maps.of(
                        "_id", tenantId + "|" + (job == null ? "ALL" : job) + "|" + dimension,
                        "tenantId", tenantId,
                        "jobId", job == null ? "ALL" : job,
                        "dimension", dimension,
                        "generatedAt", Maps.text(report, "generatedAt"),
                        "flagKeys", current,
                        "report", report));
            }
        return alerts;
    }

    private static List<String> flagKeys(Map<String, Object> report) {
        var keys = new ArrayList<String>();
        for (var transition : Maps.list(report.get("transitions")))
            for (var group : Maps.list(transition.get("groups")))
                if (Boolean.TRUE.equals(group.get("flag"))) keys.add(key(transition, group));
        return keys;
    }

    private static String key(Map<String, Object> transition, Map<String, Object> group) {
        return Maps.text(transition, "from") + ">" + Maps.text(transition, "to") + ":" + Maps.text(group, "group");
    }
}
