package com.openhire.employee.service;

import com.openhire.employee.domain.EmployeeNumber;
import com.openhire.employee.service.port.EmployeePorts.EmployeeNumbers;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

public class EmployeeNumberAllocator {
    private final EmployeeNumbers numbers;
    private final String prefix;
    private final Clock clock;

    public EmployeeNumberAllocator(EmployeeNumbers numbers, String prefix, Clock clock) {
        this.numbers = numbers;
        this.prefix = prefix;
        this.clock = clock;
    }

    public String allocate(String tenantId) {
        int year = LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear();
        return EmployeeNumber.format(prefix, year, numbers.next(tenantId, year));
    }
}
