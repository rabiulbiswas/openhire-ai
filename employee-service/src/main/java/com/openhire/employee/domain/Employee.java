package com.openhire.employee.domain;

import com.openhire.employee.domain.EmployeeTypes.Contact;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.HrSync;
import com.openhire.employee.domain.EmployeeTypes.OnboardingTask;
import com.openhire.employee.domain.EmployeeTypes.PendingEvent;
import com.openhire.employee.domain.EmployeeTypes.Snapshot;
import com.openhire.employee.domain.EmployeeTypes.TemplateTask;
import com.openhire.platform.web.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Aggregate root: one hire. Personal data lives here and is never published; events carry ids, the employee number,
 * the joining date and the designation only.
 */
@Document("employees")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_joining_page", def = "{'tenantId': 1, 'joiningDate': -1, '_id': -1}"),
    @CompoundIndex(
            name = "tenant_status_joining_page",
            def = "{'tenantId': 1, 'status': 1, 'joiningDate': -1, '_id': -1}"),
    @CompoundIndex(name = "tenant_offer", def = "{'tenantId': 1, 'offerId': 1}", unique = true),
    @CompoundIndex(name = "tenant_number", def = "{'tenantId': 1, 'employeeNo': 1}", unique = true),
    @CompoundIndex(name = "tenant_email", def = "{'tenantId': 1, 'email': 1}"),
    @CompoundIndex(name = "tenant_status_joining", def = "{'tenantId': 1, 'status': 1, 'joiningDate': -1}"),
    @CompoundIndex(name = "activation_due", def = "{'status': 1, 'joiningDate': 1}")
})
public class Employee {
    public static final String PRODUCER = "employee-service";

    @Id
    private String id;

    @Version
    private Long revision;

    private String tenantId;
    private String employeeNo;
    private String offerId;
    private String applicationId;
    private String candidateId;
    private String jobId;
    private String jobTitle;
    private String name;
    private String email;
    private String phone;
    private String joiningDate;
    private String designation;
    private String location;
    private String managerName;
    private String department;
    private Contact buddy;
    private Contact hrContact;
    private EmployeeStatus status;
    private Instant activatedAt;
    private List<OnboardingTask> tasks = new ArrayList<>();
    private boolean onboardingCompletedEmitted;
    private HrSync hrSync = HrSync.PENDING;
    private int hrisRequests;
    private Instant createdAt;
    private Instant updatedAt;

    @Field("_events")
    private List<PendingEvent> events = new ArrayList<>();

    protected Employee() {}

    public static String idFor(String tenantId, String offerId) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((tenantId + "\u0000" + offerId).getBytes(StandardCharsets.UTF_8));
            return "emp_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Employee create(
            EmployeeTypes.OfferAccepted offer, Snapshot snapshot, String employeeNo, Instant now) {
        var employee = new Employee();
        employee.id = idFor(offer.tenantId(), offer.offerId());
        employee.tenantId = offer.tenantId();
        employee.employeeNo = employeeNo;
        employee.offerId = offer.offerId();
        employee.applicationId = offer.applicationId();
        employee.candidateId = offer.candidateId();
        employee.jobId = offer.jobId();
        employee.jobTitle = offer.jobTitle();
        employee.name = snapshot.name() == null || snapshot.name().isBlank() ? offer.candidateName() : snapshot.name();
        employee.email = snapshot.email() == null ? "" : snapshot.email().trim().toLowerCase(java.util.Locale.ROOT);
        employee.phone = snapshot.phone();
        employee.joiningDate = offer.joiningDate();
        employee.designation = offer.designation();
        employee.location = offer.location();
        employee.managerName = offer.reportsTo();
        employee.status = EmployeeStatus.PRE_BOARDING;
        employee.createdAt = now;
        employee.updatedAt = now;
        var created = employee.base();
        created.put("joiningDate", employee.joiningDate);
        created.put("designation", employee.designation);
        employee.record("employee.created.v1", created, now);
        return employee;
    }

    /** PRE_BOARDING to ACTIVE; calling it again on an active employee is a conflict. */
    public void activate(Instant now) {
        if (status != EmployeeStatus.PRE_BOARDING)
            throw new IllegalStateException("Only a pre-boarding employee can be activated");
        status = EmployeeStatus.ACTIVE;
        activatedAt = now;
        updatedAt = now;
    }

    /** Builds the checklist once; returns false when it already exists so the saga step can be replayed. */
    public boolean createChecklist(List<TemplateTask> template, Instant now) {
        if (!tasks.isEmpty()) return false;
        template.forEach(task -> tasks.add(new OnboardingTask(task.id(), task.label(), task.group(), false, null)));
        updatedAt = now;
        return true;
    }

    public void setTask(String taskId, boolean done, Instant now) {
        int index = -1;
        for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id().equals(taskId)) index = i;
        if (index < 0) throw new ResourceNotFoundException("Onboarding task " + taskId + " was not found");
        var task = tasks.get(index);
        if (task.done() == done) return;
        tasks.set(index, new OnboardingTask(task.id(), task.label(), task.group(), done, done ? now : null));
        updatedAt = now;
        if (!onboardingCompletedEmitted && tasks.stream().allMatch(OnboardingTask::done)) {
            onboardingCompletedEmitted = true;
            record("employee.onboarding.completed.v1", base(), now);
        }
    }

    public int onboardingPct() {
        if (tasks.isEmpty()) return 0;
        long done = tasks.stream().filter(OnboardingTask::done).count();
        return (int) Math.round(done * 100.0 / tasks.size());
    }

    /** Emits the HRIS request for this attempt number once; a replay of the same attempt emits nothing. */
    public boolean requestHrisSync(int attempt, Instant now) {
        if (attempt <= hrisRequests) return false;
        hrisRequests = attempt;
        var payload = base();
        payload.put("joiningDate", joiningDate);
        payload.put("designation", designation);
        payload.put("attempt", attempt);
        record("employee.hris-sync.requested.v1", payload, now);
        return true;
    }

    public void hrSync(HrSync value, Instant now) {
        hrSync = value;
        updatedAt = now;
    }

    public void recordRewardEligible(String rewardId, String referrerId, Instant now) {
        var payload = base();
        payload.put("rewardId", rewardId);
        payload.put("referrerId", referrerId);
        record("employee.referral-reward.eligible.v1", payload, now);
    }

    private Map<String, Object> base() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("employeeId", id);
        payload.put("employeeNo", employeeNo);
        payload.put("applicationId", applicationId);
        payload.put("candidateId", candidateId);
        payload.put("jobId", jobId);
        payload.put("offerId", offerId);
        return payload;
    }

    private void record(String type, Map<String, Object> payload, Instant now) {
        events.add(new PendingEvent(
                UUID.randomUUID().toString(),
                PRODUCER,
                type,
                1,
                now.toString(),
                tenantId,
                "Employee",
                id,
                applicationId,
                payload));
        updatedAt = now;
    }

    public String id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String employeeNo() {
        return employeeNo;
    }

    public String offerId() {
        return offerId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String candidateId() {
        return candidateId;
    }

    public String jobTitle() {
        return jobTitle;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String joiningDate() {
        return joiningDate;
    }

    public String designation() {
        return designation;
    }

    public String location() {
        return location;
    }

    public String managerName() {
        return managerName;
    }

    public String department() {
        return department;
    }

    public Contact buddy() {
        return buddy;
    }

    public Contact hrContact() {
        return hrContact;
    }

    public EmployeeStatus status() {
        return status;
    }

    public List<OnboardingTask> tasks() {
        return List.copyOf(tasks);
    }

    public HrSync hrSync() {
        return hrSync;
    }

    public List<PendingEvent> pendingEvents() {
        return List.copyOf(events);
    }
}
