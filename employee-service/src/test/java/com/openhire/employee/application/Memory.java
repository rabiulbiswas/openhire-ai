package com.openhire.employee.application;

import com.openhire.employee.application.port.EmployeePorts.ApplicationDirectory;
import com.openhire.employee.application.port.EmployeePorts.ApplicationSnapshot;
import com.openhire.employee.application.port.EmployeePorts.EmployeeNumbers;
import com.openhire.employee.application.port.EmployeePorts.EmployeeRepository;
import com.openhire.employee.application.port.EmployeePorts.RewardRepository;
import com.openhire.employee.application.port.EmployeePorts.SagaRepository;
import com.openhire.employee.domain.Employee;
import com.openhire.employee.domain.EmployeeTypes.EmployeeStatus;
import com.openhire.employee.domain.EmployeeTypes.OfferAccepted;
import com.openhire.employee.domain.HireSaga;
import com.openhire.employee.domain.ReferralReward;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** In-memory ports and fixtures shared by the application-layer tests. */
final class Memory {
    private Memory() {}

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

    static OfferAccepted offer(String offerId, String applicationId) {
        return new OfferAccepted(
                "t1",
                offerId,
                applicationId,
                "cand-1",
                "Priya Das",
                "job-1",
                "Backend Engineer",
                "owner-1",
                "2026-11-02",
                "Senior Engineer",
                "Bengaluru",
                "Asha Rao");
    }

    static final class Employees implements EmployeeRepository {
        final Map<String, Employee> rows = new LinkedHashMap<>();
        Supplier<RuntimeException> failNextSave;

        public synchronized Employee save(Employee employee) {
            if (failNextSave != null) {
                var failure = failNextSave.get();
                failNextSave = null;
                throw failure;
            }
            rows.put(employee.id(), employee);
            return employee;
        }

        public synchronized Optional<Employee> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id))
                    .filter(row -> row.tenantId().equals(tenantId));
        }

        public synchronized Optional<Employee> findByOffer(String tenantId, String offerId) {
            return rows.values().stream()
                    .filter(row ->
                            row.tenantId().equals(tenantId) && row.offerId().equals(offerId))
                    .findFirst();
        }

        public synchronized Optional<Employee> findByEmail(String tenantId, String email) {
            return rows.values().stream()
                    .filter(row ->
                            row.tenantId().equals(tenantId) && row.email().equals(email))
                    .findFirst();
        }

        public synchronized List<Employee> list(String tenantId, EmployeeStatus status, String joiningFrom, int limit) {
            return rows.values().stream()
                    .filter(row -> row.tenantId().equals(tenantId))
                    .filter(row -> status == null || row.status() == status)
                    .filter(row -> joiningFrom == null || row.joiningDate().compareTo(joiningFrom) >= 0)
                    .sorted(Comparator.comparing(Employee::joiningDate).reversed())
                    .limit(limit)
                    .toList();
        }

        public synchronized List<Employee> findPreBoardingDue(String today, int limit) {
            return rows.values().stream()
                    .filter(row -> row.status() == EmployeeStatus.PRE_BOARDING
                            && row.joiningDate().compareTo(today) <= 0)
                    .limit(limit)
                    .toList();
        }
    }

    static final class Sagas implements SagaRepository {
        final Map<String, HireSaga> rows = new LinkedHashMap<>();

        public synchronized HireSaga insertIfAbsent(HireSaga saga) {
            return rows.computeIfAbsent(saga.id(), id -> saga);
        }

        public synchronized HireSaga save(HireSaga saga) {
            rows.put(saga.id(), saga);
            return saga;
        }

        public synchronized Optional<HireSaga> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id))
                    .filter(row -> row.tenantId().equals(tenantId));
        }

        public synchronized Optional<HireSaga> findByApplication(String tenantId, String applicationId) {
            return rows.values().stream()
                    .filter(row -> row.tenantId().equals(tenantId)
                            && row.applicationId().equals(applicationId))
                    .findFirst();
        }

        public synchronized List<HireSaga> list(String tenantId, int limit) {
            return new ArrayList<>(rows.values());
        }

        public synchronized Optional<HireSaga> claimNext(Instant now, Duration lease, String owner) {
            for (var saga : rows.values())
                if (saga.due(now)) {
                    saga.claim(now, lease, owner);
                    return Optional.of(saga);
                }
            return Optional.empty();
        }
    }

    static final class Rewards implements RewardRepository {
        final Map<String, ReferralReward> rows = new LinkedHashMap<>();

        public synchronized void insertIfAbsent(ReferralReward reward) {
            rows.putIfAbsent(reward.id(), reward);
        }

        public synchronized ReferralReward save(ReferralReward reward) {
            rows.put(reward.id(), reward);
            return reward;
        }

        public synchronized List<ReferralReward> findByReferrer(String tenantId, String referrerId) {
            return rows.values().stream()
                    .filter(row -> row.tenantId().equals(tenantId))
                    .filter(row -> row.referrerId().equals(referrerId))
                    .toList();
        }

        public synchronized List<ReferralReward> findPending(int limit) {
            return rows.values().stream()
                    .filter(row ->
                            row.status() == com.openhire.employee.domain.EmployeeTypes.RewardStatus.PENDING_PROBATION)
                    .toList();
        }
    }

    static final class Numbers implements EmployeeNumbers {
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

        public long next(String tenantId, int year) {
            return counters.computeIfAbsent(tenantId + ":" + year, key -> new AtomicLong())
                    .incrementAndGet();
        }
    }

    static final class Directory implements ApplicationDirectory {
        ApplicationSnapshot snapshot = new ApplicationSnapshot(
                "Priya Das", "Priya.Das@Example.test", "+91 98765 43210", "CAREER", "", "OFFER");
        int failures;
        int calls;

        public ApplicationSnapshot application(String tenantId, String applicationId) {
            calls++;
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("application-service unavailable");
            }
            return snapshot;
        }
    }
}
