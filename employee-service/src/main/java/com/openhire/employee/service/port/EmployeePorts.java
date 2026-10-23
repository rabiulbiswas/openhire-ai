package com.openhire.employee.service.port;

import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.HireSaga;
import com.openhire.employee.domain.ReferralReward;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound ports of the employee application layer. */
public final class EmployeePorts {
    private EmployeePorts() {}

    public interface EmployeeRepository {
        Employee save(Employee employee);

        Optional<Employee> findById(String tenantId, String id);

        Optional<Employee> findByOffer(String tenantId, String offerId);

        Optional<Employee> findByEmail(String tenantId, String email);

        List<Employee> list(String tenantId, EmployeeStatus status, String joiningFrom, int limit);

        /** Pre-boarding employees across tenants whose joining date (yyyy-MM-dd) is on or before the day. */
        List<Employee> findPreBoardingDue(String today, int limit);
    }

    public interface SagaRepository {
        /** Inserts the saga unless one with the same id exists; returns the stored saga either way. */
        HireSaga insertIfAbsent(HireSaga saga);

        HireSaga save(HireSaga saga);

        Optional<HireSaga> findById(String tenantId, String id);

        Optional<HireSaga> findByApplication(String tenantId, String applicationId);

        List<HireSaga> list(String tenantId, int limit);

        /** Atomically leases one saga whose next attempt is due, or returns empty. */
        Optional<HireSaga> claimNext(Instant now, Duration lease, String owner);
    }

    public interface RewardRepository {
        /** Inserts the reward unless it already exists. */
        void insertIfAbsent(ReferralReward reward);

        ReferralReward save(ReferralReward reward);

        List<ReferralReward> findByReferrer(String tenantId, String referrerId);

        List<ReferralReward> findPending(int limit);
    }

    /** Atomic per tenant and year sequence. */
    public interface EmployeeNumbers {
        long next(String tenantId, int year);
    }

    public record ApplicationSnapshot(
            String candidateName, String email, String phone, String source, String actorId, String stage) {}

    public interface ApplicationDirectory {
        ApplicationSnapshot application(String tenantId, String applicationId);
    }

    public interface SweepLock {
        boolean tryAcquire(String name, Duration lease);
    }
}
