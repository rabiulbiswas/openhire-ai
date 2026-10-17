package com.openhire.aiworkers.integrity.domain;

/** What a detector saw. {@code evidence} is text only and carries no personal data. */
public record Finding(String severity, double confidence, String evidence) {}
