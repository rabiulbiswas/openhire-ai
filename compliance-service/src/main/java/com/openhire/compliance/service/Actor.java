package com.openhire.compliance.service;

/** Who is acting: a staff member or the system itself. */
public record Actor(String id, String name) {
    public static final Actor SYSTEM = new Actor("system", null);
}
