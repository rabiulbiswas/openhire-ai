package com.openhire.applicationservice.messaging;

/** Stable command-routing names shared by the workflow and its Kafka publisher. */
public final class MatchingCommandContract {
    public static final String TOPIC = "matching.commands.v1";
    public static final String FIELD = "matchingCommands";

    private MatchingCommandContract() {}
}
