package com.openhire.aiworkers.integrity.domain;

/** A structured employment entry; months are {@code YYYY-MM}, a null end means current. */
public record Role(String title, String company, String start, String end, boolean fullTime) {}
