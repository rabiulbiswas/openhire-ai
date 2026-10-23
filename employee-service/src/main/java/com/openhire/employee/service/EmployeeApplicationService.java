package com.openhire.employee.service;

import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.OnboardingTask;
import com.openhire.employee.domain.EmployeeTypes.SagaStatus;
import com.openhire.employee.domain.HireSaga;
import com.openhire.employee.domain.ReferralReward;
import com.openhire.employee.dto.EmployeeViews.EmployeeDetail;
import com.openhire.employee.dto.EmployeeViews.EmployeeSummary;
import com.openhire.employee.dto.EmployeeViews.MyOnboardingView;
import com.openhire.employee.dto.EmployeeViews.OnboardingView;
import com.openhire.employee.dto.EmployeeViews.Page;
import com.openhire.employee.dto.EmployeeViews.RewardView;
import com.openhire.employee.dto.EmployeeViews.SagaView;
import com.openhire.employee.dto.EmployeeViews.TaskView;
import com.openhire.employee.service.port.EmployeePorts.EmployeeRepository;
import com.openhire.employee.service.port.EmployeePorts.RewardRepository;
import com.openhire.employee.service.port.EmployeePorts.SagaRepository;
import com.openhire.platform.audit.AuditRecord;
import com.openhire.platform.audit.AuditRecorder;
import com.openhire.platform.web.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmployeeApplicationService {
    private static final Logger LOG = LoggerFactory.getLogger(EmployeeApplicationService.class);
    private static final int LIMIT = 500;
    private static final Set<String> SAGA_STATUSES = Set.of("RUNNING", "COMPLETED", "STUCK", "FAILED");

    /** The viewer may not see or change this resource. */
    public static final class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }

    /** Who is asking: staff (HR, recruiters) manage any hire; everyone else only their own record. */
    public record Viewer(String subject, String email, boolean staff) {}

    private final EmployeeRepository employees;
    private final SagaRepository sagas;
    private final RewardRepository rewards;
    private final EmployeeSettings settings;
    private final Clock clock;
    private final AuditRecorder audit;

    public EmployeeApplicationService(
            EmployeeRepository employees,
            SagaRepository sagas,
            RewardRepository rewards,
            EmployeeSettings settings,
            Clock clock,
            AuditRecorder audit) {
        this.employees = employees;
        this.sagas = sagas;
        this.rewards = rewards;
        this.settings = settings;
        this.clock = clock;
        this.audit = audit;
    }

    public Page<EmployeeSummary> list(String tenantId, String status, String joiningFrom) {
        EmployeeStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = EmployeeStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("status must be PRE_BOARDING, ACTIVE or EXITED");
            }
        }
        String from = null;
        if (joiningFrom != null && !joiningFrom.isBlank()) {
            try {
                from = LocalDate.parse(joiningFrom.trim()).toString();
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("joiningFrom must be a date such as 2026-11-02");
            }
        }
        return Page.of(employees.list(tenantId, filter, from, LIMIT).stream()
                .map(this::summary)
                .toList());
    }

    public EmployeeDetail detail(String tenantId, String id) {
        return detail(load(tenantId, id));
    }

    public EmployeeDetail activate(String tenantId, String id, Viewer actor) {
        var activated = Retries.optimistic(() -> {
            var employee = load(tenantId, id);
            employee.activate(clock.instant());
            return employees.save(employee);
        });
        audit.record(activated(activated).user(actor.subject(), null).build());
        return detail(activated);
    }

    public OnboardingView onboarding(String tenantId, String id, Viewer viewer) {
        var employee = load(tenantId, id);
        requireOwnerOrStaff(employee, viewer);
        return new OnboardingView(employee.id(), tasks(employee));
    }

    public void toggleTask(String tenantId, String id, String taskId, boolean done, Viewer viewer) {
        Retries.optimistic(() -> {
            var employee = load(tenantId, id);
            requireOwnerOrStaff(employee, viewer);
            employee.setTask(taskId, done, clock.instant());
            return employees.save(employee);
        });
    }

    /** The signed-in user's own checklist, matched on the token's email (case-insensitive). */
    public MyOnboardingView mine(String tenantId, String email) {
        var employee = own(tenantId, email);
        return new MyOnboardingView(
                employee.id(),
                employee.employeeNo(),
                employee.joiningDate(),
                employee.name(),
                employee.buddy(),
                employee.hrContact(),
                tasks(employee));
    }

    public void toggleMine(String tenantId, String email, String taskId, boolean done) {
        Retries.optimistic(() -> {
            var employee = own(tenantId, email);
            employee.setTask(taskId, done, clock.instant());
            return employees.save(employee);
        });
    }

    public Page<SagaView> sagas(String tenantId, String status) {
        if (status != null
                && !status.isBlank()
                && !SAGA_STATUSES.contains(status.trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("status must be RUNNING, COMPLETED, STUCK or FAILED");
        var wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return Page.of(sagas.list(tenantId, LIMIT).stream()
                .filter(saga -> wanted == null || label(saga).equals(wanted))
                .sorted(Comparator.comparing(HireSaga::updatedAt).reversed())
                .map(EmployeeApplicationService::view)
                .toList());
    }

    public Page<RewardView> rewards(String tenantId, String referrerId) {
        return Page.of(rewards.findByReferrer(tenantId, referrerId).stream()
                .map(reward -> new RewardView(
                        reward.id(),
                        reward.applicationId(),
                        reward.status().name(),
                        reward.eligibleAt() == null ? null : reward.eligibleAt().toString(),
                        reward.jobTitle(),
                        reward.candidateName(),
                        reward.joiningDate()))
                .toList());
    }

    /** Activates employees on their joining date, then marks referral rewards eligible once probation has passed. */
    public void sweep() {
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (var due : employees.findPreBoardingDue(today.toString(), LIMIT)) {
            try {
                var activated = new AtomicBoolean();
                var employee = Retries.optimistic(() -> {
                    activated.set(false);
                    var current = employees.findById(due.tenantId(), due.id()).orElseThrow();
                    if (current.status() != EmployeeStatus.PRE_BOARDING) return current;
                    current.activate(clock.instant());
                    var saved = employees.save(current);
                    activated.set(true);
                    return saved;
                });
                if (activated.get()) audit.record(activated(employee).system().build());
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Employee {} not activated ({})",
                        due.id(),
                        failure.getClass().getSimpleName());
            }
        }
        for (var reward : rewards.findPending(LIMIT)) {
            try {
                markEligible(reward, today);
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Reward {} not updated ({})",
                        reward.id(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    /** Employee first: a crash in between replays the event (consumers deduplicate) rather than losing it. */
    private void markEligible(ReferralReward reward, LocalDate today) {
        var employee =
                employees.findById(reward.tenantId(), reward.employeeId()).orElse(null);
        if (employee == null || !reward.dueFor(employee, today, settings.referralRewardDays())) return;
        var now = clock.instant();
        Retries.optimistic(() -> {
            var current =
                    employees.findById(reward.tenantId(), reward.employeeId()).orElseThrow();
            current.recordRewardEligible(reward.id(), reward.referrerId(), now);
            return employees.save(current);
        });
        reward.markEligible(now);
        rewards.save(reward);
    }

    private static AuditRecord.Builder activated(Employee employee) {
        return AuditRecord.of(employee.tenantId(), "EMPLOYEE_ACTIVATED")
                .resource("Employee", employee.id())
                .about(employee.candidateId(), employee.applicationId());
    }

    private Employee load(String tenantId, String id) {
        return employees
                .findById(tenantId, id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee " + id + " was not found"));
    }

    private Employee own(String tenantId, String email) {
        if (email == null || email.isBlank()) throw new ResourceNotFoundException("No onboarding checklist found");
        return employees
                .findByEmail(tenantId, email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("No onboarding checklist found"));
    }

    private static void requireOwnerOrStaff(Employee employee, Viewer viewer) {
        boolean owner = viewer.email() != null
                && !viewer.email().isBlank()
                && viewer.email().trim().equalsIgnoreCase(employee.email());
        if (!viewer.staff() && !owner) throw new AccessDenied("HR or recruiter access required");
    }

    private EmployeeSummary summary(Employee employee) {
        return new EmployeeSummary(
                employee.id(),
                employee.name(),
                employee.employeeNo(),
                role(employee),
                employee.joiningDate(),
                employee.onboardingPct(),
                employee.hrSync().name());
    }

    private EmployeeDetail detail(Employee employee) {
        var saga = sagas.findById(employee.tenantId(), HireSaga.idFor(employee.offerId()));
        return new EmployeeDetail(
                employee.id(),
                employee.name(),
                employee.employeeNo(),
                role(employee),
                employee.joiningDate(),
                employee.onboardingPct(),
                employee.hrSync().name(),
                employee.status().name(),
                employee.department(),
                employee.designation(),
                employee.location(),
                employee.managerName(),
                employee.offerId(),
                employee.applicationId(),
                saga.map(EmployeeApplicationService::label).orElse("COMPLETED"));
    }

    private static String role(Employee employee) {
        return employee.jobTitle() == null || employee.jobTitle().isBlank()
                ? employee.designation()
                : employee.jobTitle();
    }

    private static List<TaskView> tasks(Employee employee) {
        return employee.tasks().stream().map(EmployeeApplicationService::task).toList();
    }

    private static TaskView task(OnboardingTask task) {
        return new TaskView(
                task.id(),
                task.label(),
                task.done(),
                task.group(),
                task.doneAt() == null ? null : task.doneAt().toString());
    }

    /** What the console shows: a running saga that has waited too long is STUCK. */
    static String label(HireSaga saga) {
        if (saga.status() == SagaStatus.RUNNING && saga.stuck()) return "STUCK";
        return saga.status().name();
    }

    static SagaView view(HireSaga saga) {
        return new SagaView(
                saga.id(),
                saga.offerId(),
                saga.applicationId(),
                saga.candidateName(),
                label(saga),
                saga.step().name(),
                saga.lastError(),
                saga.updatedAt() == null ? null : saga.updatedAt().toString(),
                saga.stuck(),
                saga.attempts(),
                saga.hrisStatus().name(),
                saga.employeeNo(),
                saga.employeeId());
    }
}
