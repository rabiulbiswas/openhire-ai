package com.openhire.employee.adapter.in.schedule;

import com.openhire.employee.application.EmployeeApplicationService;
import com.openhire.employee.application.HireSagaService;
import com.openhire.employee.application.port.EmployeePorts.SweepLock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class EmployeeScheduler {
    private static final int SAGAS_PER_TICK = 25;

    private final HireSagaService sagas;
    private final EmployeeApplicationService employees;
    private final SweepLock lock;

    EmployeeScheduler(HireSagaService sagas, EmployeeApplicationService employees, SweepLock lock) {
        this.sagas = sagas;
        this.employees = employees;
        this.lock = lock;
    }

    /** Safe on every instance: each saga is claimed with a lease, so two workers never run the same one. */
    @Scheduled(fixedDelayString = "${openhire.employee.saga-delay:5000}", initialDelay = 5000)
    void advanceSagas() {
        sagas.runDue(SAGAS_PER_TICK);
    }

    /** Activates employees on their joining date and releases referral rewards; one instance per tick. */
    @Scheduled(fixedDelayString = "${openhire.employee.sweep-delay:60000}", initialDelay = 15000)
    void sweep() {
        if (lock.tryAcquire("employee-sweep", Duration.ofSeconds(50))) employees.sweep();
    }
}
