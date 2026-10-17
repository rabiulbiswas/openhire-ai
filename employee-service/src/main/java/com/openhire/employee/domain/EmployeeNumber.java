package com.openhire.employee.domain;

public final class EmployeeNumber {
    private EmployeeNumber() {}

    /** EMP-2026-00039: prefix, calendar year, five-digit sequence per tenant and year. */
    public static String format(String prefix, int year, long sequence) {
        return "%s-%d-%05d".formatted(prefix, year, sequence);
    }
}
